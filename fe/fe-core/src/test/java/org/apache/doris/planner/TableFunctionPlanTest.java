// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.planner;

import org.apache.doris.analysis.CreateDbStmt;
import org.apache.doris.analysis.CreateTableStmt;
import org.apache.doris.catalog.Catalog;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.utframe.UtFrameUtils;

import org.apache.commons.io.FileUtils;

import java.io.File;
import java.util.UUID;

import org.junit.After;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TableFunctionPlanTest {
    private static String runningDir = "fe/mocked/TableFunctionPlanTest/" + UUID.randomUUID().toString() + "/";
    private static ConnectContext ctx;

    @After
    public void tearDown() throws Exception {
        FileUtils.deleteDirectory(new File(runningDir));
    }

    @BeforeClass
    public static void setUp() throws Exception {
        UtFrameUtils.createDorisCluster(runningDir);
        ctx = UtFrameUtils.createDefaultCtx();
        ctx.getSessionVariable().setEnableLateralView(true);
        String createDbStmtStr = "create database db1;";
        CreateDbStmt createDbStmt = (CreateDbStmt) UtFrameUtils.parseAndAnalyzeStmt(createDbStmtStr, ctx);
        Catalog.getCurrentCatalog().createDb(createDbStmt);
        // 3. create table tbl1
        String createTblStmtStr = "create table db1.tbl1(k1 int, k2 varchar, k3 varchar) "
                + "DUPLICATE KEY(k1) distributed by hash(k1) buckets 3 properties('replication_num' = '1');";
        CreateTableStmt createTableStmt = (CreateTableStmt) UtFrameUtils.parseAndAnalyzeStmt(createTblStmtStr, ctx);
        Catalog.getCurrentCatalog().createTable(createTableStmt);
    }

    // test planner
    /* Case1 normal table function
      select k1, e1 from table lateral view explode_split(k2, ",") tmp as e1;
     */
    @Test
    public void normalTableFunction() throws Exception {
        String sql = "desc verbose select k1, e1 from db1.tbl1 lateral view explode_split(k2, \",\") tmp as e1;";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`default_cluster:db1`.`tbl1`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("tuple ids: 0 1"));
        Assert.assertTrue(explainString.contains("TupleDescriptor{id=1, tbl=tmp, byteSize=32, materialized=true}"));
        Assert.assertTrue(explainString.contains("SlotDescriptor{id=1, col=e1, type=VARCHAR(*)}"));
    }

    /* Case2 without output explode column
      select k1 from table lateral view explode_split(k2, ",") tmp as e1;
     */
    @Test
    public void withoutOutputExplodeColumn() throws Exception {
        String sql = "desc verbose select k1 from db1.tbl1 lateral view explode_split(k2, \",\") tmp as e1;";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("OUTPUT EXPRS:`k1`"));
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`default_cluster:db1`.`tbl1`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("tuple ids: 0 1"));
        Assert.assertTrue(explainString.contains("TupleDescriptor{id=1, tbl=tmp, byteSize=32, materialized=true}"));
        Assert.assertTrue(explainString.contains("SlotDescriptor{id=1, col=e1, type=VARCHAR(*)}"));
    }

    /* Case3 group by explode column
      select k1, e1, count(*) from table lateral view explode_split(k2, ",") tmp as e1 group by k1 e1;
     */
    @Test
    public void groupByExplodeColumn() throws Exception {
        String sql = "desc verbose select k1, e1, count(*) from db1.tbl1 lateral view explode_split(k2, \",\") tmp as e1 "
                + "group by k1, e1;";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        // group by node with k1, e1
        Assert.assertTrue(explainString.contains("2:AGGREGATE (update finalize)"));
        Assert.assertTrue(explainString.contains("group by: `k1`, `e1`"));
        // table function node
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`default_cluster:db1`.`tbl1`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("tuple ids: 0 1"));
        Assert.assertTrue(explainString.contains("TupleDescriptor{id=1, tbl=tmp, byteSize=32, materialized=true}"));
        Assert.assertTrue(explainString.contains("SlotDescriptor{id=1, col=e1, type=VARCHAR(*)}"));
        // group by tuple
        Assert.assertTrue(explainString.contains("TupleDescriptor{id=2, tbl=null, byteSize=32, materialized=true}"));
    }

    /* Case4 where explode column
      select k1, e1 from table lateral view explode_split(k2, ",") tmp as e1 where e1 = "1";
     */
    @Test
    public void whereExplodeColumn() throws Exception {
        String sql = "desc verbose select k1, e1 from db1.tbl1 lateral view explode_split(k2, \",\") tmp as e1 "
                + "where e1='1'; ";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`default_cluster:db1`.`tbl1`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("PREDICATES: `e1` = '1'"));
        Assert.assertTrue(explainString.contains("tuple ids: 0 1"));
        Assert.assertTrue(explainString.contains("TupleDescriptor{id=1, tbl=tmp, byteSize=32, materialized=true}"));
        Assert.assertTrue(explainString.contains("SlotDescriptor{id=1, col=e1, type=VARCHAR(*)}"));
    }

    /* Case5 where normal column
      select k1, e1 from table lateral view explode_split(k2, ",") tmp as e1 where k1 = 1;
     */
    @Test
    public void whereNormalColumn() throws Exception {
        String sql = "desc verbose select k1, e1 from db1.tbl1 lateral view explode_split(k2, \",\") tmp as e1 "
                + "where k1=1; ";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`default_cluster:db1`.`tbl1`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("tuple ids: 0 1"));
        Assert.assertTrue(explainString.contains("TupleDescriptor{id=1, tbl=tmp, byteSize=32, materialized=true}"));
        Assert.assertTrue(explainString.contains("SlotDescriptor{id=1, col=e1, type=VARCHAR(*)}"));
        Assert.assertTrue(explainString.contains("0:OlapScanNode"));
        Assert.assertTrue(explainString.contains("PREDICATES: `k1` = 1"));
    }

    /* Case6 multi lateral view
      select k1, e1, e2 from table lateral view explode_split(k2, ",") tmp1 as e1
                                   lateral view explode_split(k2, ",") tmp2 as e2;
     */
    @Test
    public void testMultiLateralView() throws Exception {
        String sql = "desc verbose select k1, e1, e2 from db1.tbl1 lateral view explode_split(k2, \",\") tmp1 as e1"
                + " lateral view explode_split(k2, \",\") tmp2 as e2;";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`default_cluster:db1`.`tbl1`.`k2`, ',') explode_split(`default_cluster:db1`.`tbl1`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("lateral view tuple id: 1 2"));
        // lateral view 2 tuple
        Assert.assertTrue(explainString.contains("TupleDescriptor{id=1, tbl=tmp2, byteSize=32, materialized=true}"));
        Assert.assertTrue(explainString.contains("SlotDescriptor{id=1, col=e2, type=VARCHAR(*)}"));
        // lateral view 1 tuple
        Assert.assertTrue(explainString.contains("TupleDescriptor{id=2, tbl=tmp1, byteSize=32, materialized=true}"));
        Assert.assertTrue(explainString.contains("SlotDescriptor{id=2, col=e1, type=VARCHAR(*)}"));
    }

    // test explode_split function
    // k1 int ,k2 string
    /* Case1 error param
      select k1, e1 from table lateral view explode_split(k2) tmp as e1;
      select k1, e1 from table lateral view explode_split(k1) tmp as e1;
      select k1, e1 from table lateral view explode_split(k2, k2) tmp as e1;
     */
    @Test
    public void errorParam() throws Exception {
        String sql = "explain select k1, e1 from db1.tbl1 lateral view explode_split(k2) tmp as e1;";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql);
        Assert.assertTrue(explainString.contains("Doris only support `explode_split(varchar, varchar)` table function"));

        sql = "explain select k1, e1 from db1.tbl1 lateral view explode_split(k1) tmp as e1;";
        explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql);
        Assert.assertTrue(explainString.contains("Doris only support `explode_split(varchar, varchar)` table function"));

        sql = "explain select k1, e1 from db1.tbl1 lateral view explode_split(k1, k2) tmp as e1;";
        explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql);
        Assert.assertTrue(explainString.contains("Split separator of explode must be a string const"));
    }

    /* Case2 table function in where stmt
      select k1 from table where explode_split(k2, ",") = "1";
     */
    @Test
    public void tableFunctionInWhere() throws Exception {
        String sql = "explain select k1 from db1.tbl1 where explode_split(k2, \",\");";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql);
        Assert.assertTrue(
                explainString.contains("No matching function with signature: explode_split(varchar(-1), varchar(-1))."));
    }

    // test projection
    /* Case1 the source column is not be projected
      select k1, e1 from table lateral view explode_split(k2, ",") t1 as e1
      project column: k1, e1
      prune column: k2
     */
    @Test
    public void nonProjectSourceColumn() throws Exception {
        String sql = "desc verbose select k1, e1 from db1.tbl1 lateral view explode_split(k2, \",\") tmp1 as e1;";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`default_cluster:db1`.`tbl1`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("lateral view tuple id: 1"));
        Assert.assertTrue(explainString.contains("output slot id: 1 2"));
    }

    /*
    Case2 the lateral view column is projected when it is in the agg function.
    select k1, sum(cast(e1 as int)) from table lateral view explode_split(k2, ",") t1 as e1 group by k1;
    project column: k1, e1
    prune column: k2
     */
    @Test
    public void projectLateralViewColumn() throws Exception {
        String sql = "desc verbose select k1, sum(cast(e1 as int)) from db1.tbl1 lateral view explode_split(k2, \",\") tmp1 as e1"
                + " group by k1;";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`default_cluster:db1`.`tbl1`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("lateral view tuple id: 1"));
        Assert.assertTrue(explainString.contains("output slot id: 1 2"));
    }

    /*
    Case3 the source column is not be projected when it is in the where clause
    select k1, e1 from table lateral view explode_split(k2, ",") t1 as e1 where k2=1;
    project column: k1, e1
    prune column: k2
     */
    @Test
    public void nonProjectSourceColumnWhenInWhereClause() throws Exception {
        String sql = "desc verbose select k1, e1 from db1.tbl1 lateral view explode_split(k2, \",\") tmp1 as e1"
                + " where k2=1;";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`default_cluster:db1`.`tbl1`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("lateral view tuple id: 1"));
        Assert.assertTrue(explainString.contains("output slot id: 1 2"));
    }

    /*
    Case4 the source column is projected when predicate could not be pushed down
    select a.k1, t1.e1 from table a lateral view explode_split(k2, ",") t1 as e1
        right join table b on a.k1=b.k1 where k2=1;
    project column: k1, k2, e1
     */
    @Test
    public void projectSourceColumnWhenPredicateCannotPushedDown() throws Exception {
        String sql = "desc verbose select a.k1, tmp1.e1 from db1.tbl1 a lateral view explode_split(k2, \",\") tmp1 as e1"
                + " right join db1.tbl1 b on a.k1=b.k1 where a.k2=1;";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`a`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("lateral view tuple id: 1"));
        Assert.assertTrue(explainString.contains("output slot id: 0 1 2"));
    }

    /*
    Case5 There is only one source column in select items
    select a.k1 from table a lateral view explode_split(k2, ",") t1 as e1
        left join table b on a.k1=b.k1 where k2=1
    project column: k1
    prune column: k2, e1
     */
    @Test
    public void nonProjectLateralColumnAndSourceColumn() throws Exception {
        String sql = "desc verbose select a.k1 from db1.tbl1 a lateral view explode_split(k2, \",\") tmp1 as e1"
                + " left join db1.tbl1 b on a.k1=b.k1 where a.k2=1";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(`a`.`k2`, ',')"));
        Assert.assertTrue(explainString.contains("lateral view tuple id: 1"));
        Assert.assertTrue(explainString.contains("output slot id: 2"));
    }

    // scalar function in explode_split
    /*
    Case1 invalid column in scalar function
    select a.k1 from table a lateral view explode_split(t2.k2, ",") t1 as e1
    invalid column: t2.k2
    Case2
    select a.k1 from table a lateral view explode_split(k100, ",") t1 as e1
    invalid column: t1.k100
    Case3
    select a.k1 from db1.table a lateral view explode_split(db2.table.k2, ",") t1 as e1
    invalid column: db2.table.k2
     */
    @Test
    public void invalidColumnInExplodeSplit() throws Exception {
        String sql = "explain select a.k1 from db1.tbl1 a lateral view explode_split(tbl2.k1, \",\") tmp1 as e1";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("The column k1 in lateral view must come from the origin table"));
        sql = "explain select a.k1 from db1.tbl1 a lateral view explode_split(k100, \",\") tmp1 as e1";
        explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("Unknown column 'k100'"));
        sql = "explain select a.k1 from db1.tbl1 a lateral view explode_split(db2.tbl1.k2, \",\") tmp1 as e1";
        explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("The column k2 in lateral view must come from the origin table"));
    }

    /*
    Case1 invalid agg function
    select a.k1 from db1.tbl1 a lateral view explode_split(sum(a.k1), ",") tmp1 as e1
    Case2 subquery
    select a.k1 from db1.tbl1 a lateral view explode_split(a in )
     */
    @Test
    public void invalidFunctionInLateralView() throws Exception {
        String sql = "explain select a.k1 from db1.tbl1 a lateral view explode_split(sum(k1), \",\") tmp1 as e1";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("Agg function are not allowed in lateral view."));
        sql = "explain select a.k1 from db1.tbl1 a lateral view explode_split(k1=(select k1 from db1.tbl1), \",\") tmp1 as e1";
        explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("Subquery is not allowed in lateral view"));
    }

    /*
    Case1 valid scalar function
    select a.k1 from db1.tbl1 a lateral view explode_split(concat('a', ',', 'b'), ",") tmp1 as e1
     */
    @Test
    public void scalarFunctionInLateralView() throws Exception {
        String sql = "desc verbose select a.k1 from db1.tbl1 a lateral view explode_split(concat(k2, ',' , k3), \",\") tmp1 as e1 ";
        String explainString = UtFrameUtils.getSQLPlanOrErrorMsg(ctx, sql, true);
        Assert.assertTrue(explainString.contains("1:TABLE FUNCTION NODE"));
        Assert.assertTrue(explainString.contains("table function: explode_split(concat(`a`.`k2`, ',', `a`.`k3`), ',')"));
        Assert.assertTrue(explainString.contains("lateral view tuple id: 1"));
        Assert.assertTrue(explainString.contains("output slot id: 3"));
        Assert.assertTrue(explainString.contains("SlotDescriptor{id=0, col=k2, type=VARCHAR(*)}"));
        Assert.assertTrue(explainString.contains("SlotDescriptor{id=1, col=k3, type=VARCHAR(*)}"));
    }
}

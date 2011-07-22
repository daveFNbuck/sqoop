/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.cloudera.sqoop;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.StringUtils;

import com.cloudera.sqoop.manager.ConnManager;
import com.cloudera.sqoop.manager.HsqldbManager;
import com.cloudera.sqoop.manager.ManagerFactory;
import com.cloudera.sqoop.metastore.TestSessions;
import com.cloudera.sqoop.testutil.BaseSqoopTestCase;
import com.cloudera.sqoop.testutil.CommonArgs;
import com.cloudera.sqoop.tool.ImportTool;
import com.cloudera.sqoop.tool.SessionTool;

import junit.framework.TestCase;

import java.sql.Connection;

/**
 * Test the incremental import functionality.
 *
 * These all make use of the auto-connect hsqldb-based metastore.
 * The metastore URL is configured to be in-memory, and drop all
 * state between individual tests.
 */
public class TestIncrementalImport extends TestCase {

  public static final Log LOG = LogFactory.getLog(
      TestIncrementalImport.class.getName());

  // What database do we read from.
  public static final String SOURCE_DB_URL = "jdbc:hsqldb:mem:incremental";

  @Override
  public void setUp() throws Exception {
    // Delete db state between tests.
    TestSessions.resetSessionSchema();
    resetSourceDataSchema();
  }

  public static void resetSourceDataSchema() throws SQLException {
    SqoopOptions options = new SqoopOptions();
    options.setConnectString(SOURCE_DB_URL);
    TestSessions.resetSchema(options);
  }

  public static Configuration newConf() {
    return TestSessions.newConf();
  }

  /**
   * Assert that a table has a specified number of rows.
   */
  private void assertRowCount(String table, int numRows) throws SQLException {
    SqoopOptions options = new SqoopOptions();
    options.setConnectString(SOURCE_DB_URL);
    HsqldbManager manager = new HsqldbManager(options);
    Connection c = manager.getConnection();
    PreparedStatement s = null;
    ResultSet rs = null;
    try {
      s = c.prepareStatement("SELECT COUNT(*) FROM " + table);
      rs = s.executeQuery();
      if (!rs.next()) {
        fail("No resultset");
      }
      int realNumRows = rs.getInt(1);
      assertEquals(numRows, realNumRows);
      LOG.info("Expected " + numRows + " rows -- ok.");
    } finally {
      if (null != s) {
        try {
          s.close();
        } catch (SQLException sqlE) {
          LOG.warn("exception: " + sqlE);
        } 
      }

      if (null != rs) {
        try {
          rs.close();
        } catch (SQLException sqlE) {
          LOG.warn("exception: " + sqlE);
        } 
      }
    }
  }

  /**
   * Insert rows with id = [low, hi) into tableName.
   */
  private void insertIdRows(String tableName, int low, int hi)
      throws SQLException {
    SqoopOptions options = new SqoopOptions();
    options.setConnectString(SOURCE_DB_URL);
    HsqldbManager manager = new HsqldbManager(options);
    Connection c = manager.getConnection();
    PreparedStatement s = null;
    try {
      s = c.prepareStatement("INSERT INTO " + tableName + " VALUES(?)");
      for (int i = low; i < hi; i++) {
        s.setInt(1, i);
        s.executeUpdate();
      }

      c.commit();
    } finally {
      s.close();
    }
  }

  /**
   * Insert rows with id = [low, hi) into tableName with
   * the timestamp column set to the specified ts.
   */
  private void insertIdTimestampRows(String tableName, int low, int hi,
      Timestamp ts) throws SQLException {
    LOG.info("Inserting id rows in [" + low + ", " + hi + ") @ " + ts);
    SqoopOptions options = new SqoopOptions();
    options.setConnectString(SOURCE_DB_URL);
    HsqldbManager manager = new HsqldbManager(options);
    Connection c = manager.getConnection();
    PreparedStatement s = null;
    try {
      s = c.prepareStatement("INSERT INTO " + tableName + " VALUES(?,?)");
      for (int i = low; i < hi; i++) {
        s.setInt(1, i);
        s.setTimestamp(2, ts);
        s.executeUpdate();
      }

      c.commit();
    } finally {
      s.close();
    }
  }

  /**
   * Create a table with an 'id' column full of integers.
   */
  private void createIdTable(String tableName, int insertRows)
      throws SQLException {
    SqoopOptions options = new SqoopOptions();
    options.setConnectString(SOURCE_DB_URL);
    HsqldbManager manager = new HsqldbManager(options);
    Connection c = manager.getConnection();
    PreparedStatement s = null;
    try {
      s = c.prepareStatement("CREATE TABLE " + tableName + "(id INT NOT NULL)");
      s.executeUpdate();
      c.commit();
      insertIdRows(tableName, 0, insertRows);
    } finally {
      s.close();
    }
  }

  /**
   * Create a table with an 'id' column full of integers and a 
   * last_modified column with timestamps.
   */
  private void createTimestampTable(String tableName, int insertRows,
      Timestamp baseTime) throws SQLException {
    SqoopOptions options = new SqoopOptions();
    options.setConnectString(SOURCE_DB_URL);
    HsqldbManager manager = new HsqldbManager(options);
    Connection c = manager.getConnection();
    PreparedStatement s = null;
    try {
      s = c.prepareStatement("CREATE TABLE " + tableName + "(id INT NOT NULL, "
          + "last_modified TIMESTAMP)");
      s.executeUpdate();
      c.commit();
      insertIdTimestampRows(tableName, 0, insertRows, baseTime);
    } finally {
      s.close();
    }
  }

  /**
   * Delete all files in a directory for a table.
   */
  public void clearDir(String tableName) {
    try {
      FileSystem fs = FileSystem.getLocal(new Configuration());
      Path warehouse = new Path(BaseSqoopTestCase.LOCAL_WAREHOUSE_DIR);
      Path tableDir = new Path(warehouse, tableName);
      fs.delete(tableDir, true);
    } catch (Exception e) {
      fail("Got unexpected exception: " + StringUtils.stringifyException(e));
    }
  }

  /**
   * Look at a directory that should contain files full of an imported 'id'
   * column. Assert that all numbers in [0, expectedNums) are present
   * in order.
   */
  public void assertDirOfNumbers(String tableName, int expectedNums) {
    try {
      FileSystem fs = FileSystem.getLocal(new Configuration());
      Path warehouse = new Path(BaseSqoopTestCase.LOCAL_WAREHOUSE_DIR);
      Path tableDir = new Path(warehouse, tableName);
      FileStatus [] stats = fs.listStatus(tableDir);
      String [] fileNames = new String[stats.length];
      for (int i = 0; i < stats.length; i++) {
        fileNames[i] = stats[i].getPath().toString();
      }

      Arrays.sort(fileNames);

      // Read all the files in sorted order, adding the value lines to the list.
      List<String> receivedNums = new ArrayList<String>();
      for (String fileName : fileNames) {
        if (fileName.startsWith("_") || fileName.startsWith(".")) {
          continue;
        }

        BufferedReader r = new BufferedReader(
            new InputStreamReader(fs.open(new Path(fileName))));
        try {
          while (true) {
            String s = r.readLine();
            if (null == s) {
              break;
            }

            receivedNums.add(s.trim());
          }
        } finally {
          r.close();
        }
      }

      assertEquals(expectedNums, receivedNums.size());

      // Compare the received values with the expected set.
      for (int i = 0; i < expectedNums; i++) {
        assertEquals((int) i, (int) Integer.valueOf(receivedNums.get(i)));
      }
    } catch (Exception e) {
      fail("Got unexpected exception: " + StringUtils.stringifyException(e));
    }
  }

  /**
   * Assert that a directory contains a file with exactly one line
   * in it, containing the prescribed number 'val'.
   */
  public void assertSpecificNumber(String tableName, int val) {
    try {
      FileSystem fs = FileSystem.getLocal(new Configuration());
      Path warehouse = new Path(BaseSqoopTestCase.LOCAL_WAREHOUSE_DIR);
      Path tableDir = new Path(warehouse, tableName);
      FileStatus [] stats = fs.listStatus(tableDir);
      String [] fileNames = new String[stats.length];
      for (int i = 0; i < stats.length; i++) {
        fileNames[i] = stats[i].getPath().toString();
      }

      // Read the first file that is not a hidden file.
      boolean foundVal = false;
      for (String fileName : fileNames) {
        if (fileName.startsWith("_") || fileName.startsWith(".")) {
          continue;
        }

        if (foundVal) {
          // Make sure we don't have two or more "real" files in the dir.
          fail("Got an extra data-containing file in this directory.");
        }

        BufferedReader r = new BufferedReader(
            new InputStreamReader(fs.open(new Path(fileName))));
        try {
          String s = r.readLine();
          if (null == s) {
            fail("Unexpected empty file " + fileName + ".");
          }
          assertEquals(val, (int) Integer.valueOf(s.trim()));

          String nextLine = r.readLine();
          if (nextLine != null) {
            fail("Expected only one result, but got another line: " + nextLine);
          }

          // Successfully got the value we were looking for.
          foundVal = true;
        } finally {
          r.close();
        }
      }
    } catch (Exception e) {
      fail("Got unexpected exception: " + StringUtils.stringifyException(e));
    }
  }

  public void runImport(SqoopOptions options, List<String> args) {
    try {
      Sqoop importer = new Sqoop(new ImportTool(), options.getConf(), options);
      int ret = Sqoop.runSqoop(importer, args.toArray(new String[0]));
      assertEquals("Failure during job", 0, ret);
    } catch (Exception e) {
      LOG.error("Got exception running Sqoop: "
          + StringUtils.stringifyException(e));
      throw new RuntimeException(e);
    }
  }

  /**
   * Return a list of arguments to import the specified table.
   */
  private List<String> getArgListForTable(String tableName, boolean commonArgs,
      boolean isAppend) {
    List<String> args = new ArrayList<String>();
    if (commonArgs) {
      CommonArgs.addHadoopFlags(args);
    }
    args.add("--connect");
    args.add(SOURCE_DB_URL);
    args.add("--table");
    args.add(tableName);
    args.add("--warehouse-dir");
    args.add(BaseSqoopTestCase.LOCAL_WAREHOUSE_DIR);
    if (isAppend) {
      args.add("--incremental");
      args.add("append");
      args.add("--check-column");
      args.add("id");
    } else {
      args.add("--incremental");
      args.add("lastmodified");
      args.add("--check-column");
      args.add("last_modified");
    }
    args.add("--columns");
    args.add("id");
    args.add("-m");
    args.add("1");
    
    return args;
  }

  /**
   * Create a session with the specified name, where the session performs
   * an import configured with 'sessionArgs'.
   */
  private void createSession(String sessionName, List<String> sessionArgs) {
    createSession(sessionName, sessionArgs, newConf());
  }

  /**
   * Create a session with the specified name, where the session performs
   * an import configured with 'sessionArgs', using the provided configuration
   * as defaults.
   */
  private void createSession(String sessionName, List<String> sessionArgs,
      Configuration conf) {
    try {
      SqoopOptions options = new SqoopOptions();
      options.setConf(conf);
      Sqoop makeSession = new Sqoop(new SessionTool(), conf, options);
      
      List<String> args = new ArrayList<String>();
      args.add("--create");
      args.add(sessionName);
      args.add("--");
      args.add("import");
      args.addAll(sessionArgs);

      int ret = Sqoop.runSqoop(makeSession, args.toArray(new String[0]));
      assertEquals("Failure during job to create session", 0, ret);
    } catch (Exception e) {
      LOG.error("Got exception running Sqoop to create session: "
          + StringUtils.stringifyException(e));
      throw new RuntimeException(e);
    }
  }

  /**
   * Run the specified session.
   */
  private void runSession(String sessionName) {
    runSession(sessionName, newConf());
  }

  /**
   * Run the specified session.
   */
  private void runSession(String sessionName, Configuration conf) {
    try {
      SqoopOptions options = new SqoopOptions();
      options.setConf(conf);
      Sqoop runSession = new Sqoop(new SessionTool(), conf, options);
      
      List<String> args = new ArrayList<String>();
      args.add("--exec");
      args.add(sessionName);

      int ret = Sqoop.runSqoop(runSession, args.toArray(new String[0]));
      assertEquals("Failure during job to run session", 0, ret);
    } catch (Exception e) {
      LOG.error("Got exception running Sqoop to run session: "
          + StringUtils.stringifyException(e));
      throw new RuntimeException(e);
    }
  }

  // Incremental import of an empty table, no metastore.
  public void testEmptyAppendImport() throws Exception {
    final String TABLE_NAME = "emptyAppend1";
    createIdTable(TABLE_NAME, 0);
    List<String> args = getArgListForTable(TABLE_NAME, true, true);

    Configuration conf = newConf();
    SqoopOptions options = new SqoopOptions();
    options.setConf(conf);
    runImport(options, args);

    assertDirOfNumbers(TABLE_NAME, 0);
  }

  // Incremental import of a filled table, no metastore.
  public void testFullAppendImport() throws Exception {
    final String TABLE_NAME = "fullAppend1";
    createIdTable(TABLE_NAME, 10);
    List<String> args = getArgListForTable(TABLE_NAME, true, true);

    Configuration conf = newConf();
    SqoopOptions options = new SqoopOptions();
    options.setConf(conf);
    runImport(options, args);

    assertDirOfNumbers(TABLE_NAME, 10);
  }

  public void testEmptySessionAppend() throws Exception {
    // Create a session and run an import on an empty table.
    // Nothing should happen.

    final String TABLE_NAME = "emptySession";
    createIdTable(TABLE_NAME, 0);

    List<String> args = getArgListForTable(TABLE_NAME, false, true);
    createSession("emptySession", args);
    runSession("emptySession");
    assertDirOfNumbers(TABLE_NAME, 0);

    // Running the session a second time should result in
    // nothing happening, it's still empty.
    runSession("emptySession");
    assertDirOfNumbers(TABLE_NAME, 0);
  }

  public void testEmptyThenFullSessionAppend() throws Exception {
    // Create an empty table. Import it; nothing happens.
    // Add some rows. Verify they are appended.

    final String TABLE_NAME = "emptyThenFull";
    createIdTable(TABLE_NAME, 0);

    List<String> args = getArgListForTable(TABLE_NAME, false, true);
    createSession(TABLE_NAME, args);
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 0);

    // Now add some rows.
    insertIdRows(TABLE_NAME, 0, 10);

    // Running the session a second time should import 10 rows.
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 10);

    // Add some more rows.
    insertIdRows(TABLE_NAME, 10, 20);

    // Import only those rows.
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 20);
  }

  public void testAppend() throws Exception {
    // Create a table with data in it; import it.
    // Then add more data, verify that only the incremental data is pulled.

    final String TABLE_NAME = "append";
    createIdTable(TABLE_NAME, 10);

    List<String> args = getArgListForTable(TABLE_NAME, false, true);
    createSession(TABLE_NAME, args);
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 10);

    // Add some more rows.
    insertIdRows(TABLE_NAME, 10, 20);

    // Import only those rows.
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 20);
  }

  public void testEmptyLastModified() throws Exception {
    final String TABLE_NAME = "emptyLastModified";
    createTimestampTable(TABLE_NAME, 0, null);
    List<String> args = getArgListForTable(TABLE_NAME, true, false);

    Configuration conf = newConf();
    SqoopOptions options = new SqoopOptions();
    options.setConf(conf);
    runImport(options, args);

    assertDirOfNumbers(TABLE_NAME, 0);
  }

  public void testFullLastModifiedImport() throws Exception {
    // Given a table of rows imported in the past,
    // see that they are imported.
    final String TABLE_NAME = "fullLastModified";
    Timestamp thePast = new Timestamp(System.currentTimeMillis() - 100); 
    createTimestampTable(TABLE_NAME, 10, thePast);

    List<String> args = getArgListForTable(TABLE_NAME, true, false);

    Configuration conf = newConf();
    SqoopOptions options = new SqoopOptions();
    options.setConf(conf);
    runImport(options, args);

    assertDirOfNumbers(TABLE_NAME, 10);
  }

  public void testNoImportFromTheFuture() throws Exception {
    // If last-modified dates for writes are serialized to be in the
    // future w.r.t. an import, do not import these rows.

    final String TABLE_NAME = "futureLastModified";
    Timestamp theFuture = new Timestamp(System.currentTimeMillis() + 1000000);
    createTimestampTable(TABLE_NAME, 10, theFuture);

    List<String> args = getArgListForTable(TABLE_NAME, true, false);

    Configuration conf = newConf();
    SqoopOptions options = new SqoopOptions();
    options.setConf(conf);
    runImport(options, args);

    assertDirOfNumbers(TABLE_NAME, 0);
  }

  public void testEmptySessionLastMod() throws Exception {
    // Create a session and run an import on an empty table.
    // Nothing should happen.

    final String TABLE_NAME = "emptySessionLastMod";
    createTimestampTable(TABLE_NAME, 0, null);

    List<String> args = getArgListForTable(TABLE_NAME, false, false);
    args.add("--append");
    createSession("emptySessionLastMod", args);
    runSession("emptySessionLastMod");
    assertDirOfNumbers(TABLE_NAME, 0);

    // Running the session a second time should result in
    // nothing happening, it's still empty.
    runSession("emptySessionLastMod");
    assertDirOfNumbers(TABLE_NAME, 0);
  }

  public void testEmptyThenFullSessionLastMod() throws Exception {
    // Create an empty table. Import it; nothing happens.
    // Add some rows. Verify they are appended.

    final String TABLE_NAME = "emptyThenFullTimestamp";
    createTimestampTable(TABLE_NAME, 0, null);

    List<String> args = getArgListForTable(TABLE_NAME, false, false);
    args.add("--append");
    createSession(TABLE_NAME, args);
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 0);

    long importWasBefore = System.currentTimeMillis();

    // Let some time elapse.
    Thread.sleep(50);

    long rowsAddedTime = System.currentTimeMillis() - 5;

    // Check: we are adding rows after the previous import time
    // and before the current time.
    assertTrue(rowsAddedTime > importWasBefore);
    assertTrue(rowsAddedTime < System.currentTimeMillis());

    insertIdTimestampRows(TABLE_NAME, 0, 10, new Timestamp(rowsAddedTime));

    // Running the session a second time should import 10 rows.
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 10);

    // Add some more rows.
    importWasBefore = System.currentTimeMillis();
    Thread.sleep(50);
    rowsAddedTime = System.currentTimeMillis() - 5;
    assertTrue(rowsAddedTime > importWasBefore);
    assertTrue(rowsAddedTime < System.currentTimeMillis());
    insertIdTimestampRows(TABLE_NAME, 10, 20, new Timestamp(rowsAddedTime));

    // Import only those rows.
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 20);
  }

  public void testAppendWithTimestamp() throws Exception {
    // Create a table with data in it; import it.
    // Then add more data, verify that only the incremental data is pulled.

    final String TABLE_NAME = "appendTimestamp";
    Timestamp thePast = new Timestamp(System.currentTimeMillis() - 100);
    createTimestampTable(TABLE_NAME, 10, thePast);

    List<String> args = getArgListForTable(TABLE_NAME, false, false);
    args.add("--append");
    createSession(TABLE_NAME, args);
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 10);

    // Add some more rows.
    long importWasBefore = System.currentTimeMillis();
    Thread.sleep(50);
    long rowsAddedTime = System.currentTimeMillis() - 5;
    assertTrue(rowsAddedTime > importWasBefore);
    assertTrue(rowsAddedTime < System.currentTimeMillis());
    insertIdTimestampRows(TABLE_NAME, 10, 20, new Timestamp(rowsAddedTime));

    // Import only those rows.
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 20);
  }

  public void testModifyWithTimestamp() throws Exception {
    // Create a table with data in it; import it.
    // Then modify some existing rows, and verify that we only grab
    // those rows.

    final String TABLE_NAME = "modifyTimestamp";
    Timestamp thePast = new Timestamp(System.currentTimeMillis() - 100);
    createTimestampTable(TABLE_NAME, 10, thePast);

    List<String> args = getArgListForTable(TABLE_NAME, false, false);
    createSession(TABLE_NAME, args);
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 10);

    // Modify a row.
    long importWasBefore = System.currentTimeMillis();
    Thread.sleep(50);
    long rowsAddedTime = System.currentTimeMillis() - 5;
    assertTrue(rowsAddedTime > importWasBefore);
    assertTrue(rowsAddedTime < System.currentTimeMillis());
    SqoopOptions options = new SqoopOptions();
    options.setConnectString(SOURCE_DB_URL);
    HsqldbManager manager = new HsqldbManager(options);
    Connection c = manager.getConnection();
    PreparedStatement s = null;
    try {
      s = c.prepareStatement("UPDATE " + TABLE_NAME
          + " SET id=?, last_modified=? WHERE id=?");
      s.setInt(1, 4000); // the first row should have '4000' in it now.
      s.setTimestamp(2, new Timestamp(rowsAddedTime));
      s.setInt(3, 0);
      s.executeUpdate();
      c.commit();
    } finally {
      s.close();
    }

    // Import only the new row.
    clearDir(TABLE_NAME);
    runSession(TABLE_NAME);
    assertSpecificNumber(TABLE_NAME, 4000);
  }

  /**
   * ManagerFactory returning an HSQLDB ConnManager which allows you to
   * specify the current database timestamp.
   */
  public static class InstrumentHsqldbManagerFactory extends ManagerFactory {
    @Override
    public ConnManager accept(SqoopOptions options) {
      LOG.info("Using instrumented manager");
      return new InstrumentHsqldbManager(options);
    }
  }

  /**
   * Hsqldb ConnManager that lets you set the current reported timestamp
   * from the database, to allow testing of boundary conditions for imports.
   */
  public static class InstrumentHsqldbManager extends HsqldbManager {
    private static Timestamp curTimestamp;

    public InstrumentHsqldbManager(SqoopOptions options) {
      super(options);
    }

    @Override
    public Timestamp getCurrentDbTimestamp() {
      return InstrumentHsqldbManager.curTimestamp;
    }

    public static void setCurrentDbTimestamp(Timestamp t) {
      InstrumentHsqldbManager.curTimestamp = t;
    }
  }

  public void testTimestampBoundary() throws Exception {
    // Run an import, and then insert rows with the last-modified timestamp
    // set to the exact time when the first import runs. Run a second import
    // and ensure that we pick up the new data.

    long now = System.currentTimeMillis();

    final String TABLE_NAME = "boundaryTimestamp";
    Timestamp thePast = new Timestamp(now - 100);
    createTimestampTable(TABLE_NAME, 10, thePast);

    Timestamp firstJobTime = new Timestamp(now);
    InstrumentHsqldbManager.setCurrentDbTimestamp(firstJobTime);

    // Configure the job to use the instrumented Hsqldb manager.
    Configuration conf = newConf();
    conf.set(ConnFactory.FACTORY_CLASS_NAMES_KEY,
        InstrumentHsqldbManagerFactory.class.getName());

    List<String> args = getArgListForTable(TABLE_NAME, false, false);
    args.add("--append");
    createSession(TABLE_NAME, args, conf);
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 10);

    // Add some more rows with the timestamp equal to the job run timestamp.
    insertIdTimestampRows(TABLE_NAME, 10, 20, firstJobTime);
    assertRowCount(TABLE_NAME, 20);

    // Run a second job with the clock advanced by 100 ms.
    Timestamp secondJobTime = new Timestamp(now + 100);
    InstrumentHsqldbManager.setCurrentDbTimestamp(secondJobTime);

    // Import only those rows.
    runSession(TABLE_NAME);
    assertDirOfNumbers(TABLE_NAME, 20);
  }
}

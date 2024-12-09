/*
  Copyright (c) 2020, 2021, Oracle and/or its affiliates.

  This software is dual-licensed to you under the Universal Permissive License 
  (UPL) 1.0 as shown at https://oss.oracle.com/licenses/upl or Apache License
  2.0 as shown at http://www.apache.org/licenses/LICENSE-2.0. You may choose
  either license.

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

     https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
*/

package oracle.r2dbc.impl;

import io.r2dbc.spi.Batch;
import io.r2dbc.spi.R2dbcException;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.sql.Connection;
import java.time.Duration;
import java.util.LinkedList;
import java.util.Queue;

import static oracle.r2dbc.impl.OracleR2dbcExceptions.requireNonNull;
import static oracle.r2dbc.impl.OracleR2dbcExceptions.requireOpenConnection;

/**
 * <p>
 * Implementation of the {@link Batch} SPI for Oracle Database. This SPI
 * implementation executes an ordered sequence of arbitrary SQL statements
 * using a JDBC connection. JDBC API calls are adapted into Reactive Streams
 * APIs using a {@link ReactiveJdbcAdapter}.
 * </p><p>
 * Oracle Database supports batch execution of parameterized DML statements,
 * but does not support batch execution of arbitrary SQL statements. This
 * implementation reflects the capabilities of Oracle Database; It does not
 * offer any performance benefit compared to individually executing each
 * statement in the batch.
 * </p>
 *
 * @author  harayuanwang, michael-a-mcmahon
 * @since   0.1.0
 */
final class OracleBatchImpl implements Batch {

  /** The OracleConnectionImpl that created this Batch */
  private final OracleConnectionImpl r2dbcConnection;

  /**
   * JDBC connection to an Oracle Database which executes this batch.
   */
  private final Connection jdbcConnection;

  /**
   * Timeout applied to each statement this {@code Batch} executes;
   */
  private final Duration timeout;

  /**
   * Ordered sequence of SQL commands that have been added to this batch. May
   * be empty.
   */
  private Queue<OracleStatementImpl> statements = new LinkedList<>();

  /**
   * Constructs a new batch that uses the specified {@code adapter} to execute
   * SQL statements with a {@code jdbcConnection}.
   * @param timeout Timeout applied to each statement this batch executes.
   * Not null. Not negative.
   * @param r2dbcConnection R2DBC connection that created this batch. Not null.
   */
  OracleBatchImpl(Duration timeout, OracleConnectionImpl r2dbcConnection) {
    this.timeout = timeout;
    this.r2dbcConnection = r2dbcConnection;
    this.jdbcConnection = r2dbcConnection.jdbcConnection();
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by adding a {@code sql} command to the
   * end of the command sequence of the current batch.
   * </p>
   */
  @Override
  public Batch add(String sql) {
    requireOpenConnection(jdbcConnection);
    requireNonNull(sql, "sql is null");
    statements.add(
      new OracleStatementImpl(sql, timeout, r2dbcConnection));
    return this;
  }

  /**
   * {@inheritDoc}
   * <p>
   * Implements the R2DBC SPI method by executing the SQL statements that have
   * been added to the current batch since the previous execution. Statements
   * are executed in the order they were added. Calling this method clears all
   * statements that have been added to the current batch.
   * </p><p>
   * If the execution of any statement in the sequence results in a failure,
   * then the returned publisher emits {@code onError} with an
   * {@link R2dbcException} that describes the failure, and all subsequent
   * statements in the sequence are not executed.
   * </p><p>
   * The returned publisher begins executing the batch <i>after</i> a
   * subscriber subscribes, <i>before</i> the subscriber emits a {@code
   * request} signal. The returned publisher does not support multiple
   * subscribers. After one subscriber has subscribed, the returned publisher
   * signals {@code onError} with {@code IllegalStateException} to any
   * subsequent subscribers.
   * </p>
   */
  @Override
  public Publisher<OracleResultImpl> execute() {
    requireOpenConnection(jdbcConnection);
    Queue<OracleStatementImpl> currentStatements = statements;
    statements = new LinkedList<>();
    return Flux.fromIterable(currentStatements)
      .flatMapSequential(OracleStatementImpl::execute);
  }

}


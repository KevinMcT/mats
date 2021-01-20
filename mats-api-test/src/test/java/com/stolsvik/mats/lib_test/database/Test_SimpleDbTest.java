package com.stolsvik.mats.lib_test.database;

import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;

import com.stolsvik.mats.MatsFactory.ContextLocal;
import com.stolsvik.mats.test.junit.Rule_Mats;
import com.stolsvik.mats.lib_test.DataTO;
import com.stolsvik.mats.lib_test.StateTO;
import com.stolsvik.mats.lib_test.basics.Test_SimplestServiceRequest;
import com.stolsvik.mats.test.MatsTestHelp;
import com.stolsvik.mats.test.MatsTestLatch.Result;
import com.stolsvik.mats.test.TestH2DataSource;

/**
 * Simple test that looks quite a bit like {@link Test_SimplestServiceRequest}, only the Initiator now populate a table
 * with some data, which the Mats service retrieves and replies with.
 * <p>
 * ASCII-artsy, it looks like this:
 *
 * <pre>
 * [Initiator]   - inserts into database - request
 *     [Service] - fetches from database - reply
 * [Terminator]
 * </pre>
 *
 * @author Endre Stølsvik - 2015 - http://endre.stolsvik.com
 */
public class Test_SimpleDbTest {
    @ClassRule
    public static final Rule_Mats MATS = Rule_Mats.createWithDb();

    private static final String SERVICE = MatsTestHelp.endpointId("Service");
    private static final String TERMINATOR = MatsTestHelp.terminator();

    @BeforeClass
    public static void setupService() {
        MATS.getMatsFactory().single(SERVICE, DataTO.class, DataTO.class,
                (context, dto) -> {
                    Optional<Connection> connectionAttribute = context.getAttribute(Connection.class);
                    if (!connectionAttribute.isPresent()) {
                        throw new AssertionError("Missing context.getAttribute(Connection.class)");
                    }
                    Connection sqlConnection = connectionAttribute.get();

                    Optional<Connection> contextLocalConnectionAttribute = ContextLocal.getAttribute(Connection.class);
                    if (!contextLocalConnectionAttribute.isPresent()) {
                        throw new AssertionError("Missing ContextLocal.getAttribute(Connection.class)");
                    }

                    // These should be the same.
                    Assert.assertSame(sqlConnection, contextLocalConnectionAttribute.get());

                    // :: Get the data from the SQL table
                    List<String> data = TestH2DataSource.getDataFromDataTable(sqlConnection);
                    Assert.assertEquals(1, data.size());

                    return new DataTO(dto.number * 2, dto.string + ":FromService:" + data.get(0));
                });
    }

    @BeforeClass
    public static void setupTerminator() {
        MATS.getMatsFactory().terminator(TERMINATOR, StateTO.class, DataTO.class,
                (context, sto, dto) -> {
                    MATS.getMatsTestLatch().resolve(sto, dto);
                });

    }

    @Test
    public void checkThatDataSourceWorks() {
        MATS.getDataSource().createDataTable();

        DataTO dto = new DataTO(42, "TheAnswer");
        StateTO sto = new StateTO(420, 420.024);
        String randomData = UUID.randomUUID().toString();

        // :: Insert into 'datatable' and send the request to SERVICE.
        MATS.getMatsInitiator().initiateUnchecked(
                (init) -> {
                    // :: Assert that SQL Connection is in places where it should be
                    Optional<Connection> connectionAttribute = init.getAttribute(Connection.class);
                    if (!connectionAttribute.isPresent()) {
                        throw new AssertionError("Missing matsInitiate.getAttribute(Connection.class)");
                    }
                    Connection sqlConnection = connectionAttribute.get();

                    Optional<Connection> contextLocalConnectionAttribute = ContextLocal.getAttribute(Connection.class);
                    if (!contextLocalConnectionAttribute.isPresent()) {
                        throw new AssertionError("Missing ContextLocal.getAttribute(Connection.class)");
                    }

                    // These should be the same.
                    Assert.assertSame(sqlConnection, contextLocalConnectionAttribute.get());

                    // :: Stick some data in table.
                    TestH2DataSource.insertDataIntoDataTable(sqlConnection, randomData);

                    // :: Send the request
                    init.traceId(MatsTestHelp.traceId())
                            .from(MatsTestHelp.from("checkThatDataSourceWorks"))
                            .to(SERVICE)
                            .replyTo(TERMINATOR, sto)
                            .request(dto);
                });

        // Wait synchronously for terminator to finish.
        Result<StateTO, DataTO> result = MATS.getMatsTestLatch().waitForResult();
        Assert.assertEquals(sto, result.getState());
        Assert.assertEquals(new DataTO(dto.number * 2, dto.string + ":FromService:" + randomData), result.getData());
    }
}

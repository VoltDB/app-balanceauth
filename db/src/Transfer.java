/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package procedures;

//import java.math.BigDecimal;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import org.voltdb.ProcInfo;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltTableRow;
import org.voltdb.VoltType;
import org.voltdb.types.TimestampType;

/** A VoltDB stored procedure is a Java class defining one or
 * more SQL statements and implementing a public
 * VoltTable[] run method. VoltDB requires a
 * ProcInfo annotation providing metadata for the
 * procedure.  The run method is
 * defined to accept one or more parameters. These parameters take the
 * values the client passes via the
 * Client.callProcedure invocation.
 * The VoltDB User Guide (https://community.voltdb.com/documentation)
 * specifies valid stored procedure definitions,
 * including valid run method parameter types, required annotation
 * metadata, and correct use the Volt query interface.
 */

public class Transfer extends VoltProcedure {

    public final SQLStmt getAcct = new SQLStmt("SELECT * FROM card_account WHERE pan = ?;");

    public final SQLStmt updateAcct = new SQLStmt("UPDATE card_account SET " +
                                                  " balance = balance + ?," +
                                                  " available_balance = available_balance + ?," +
                                                  " last_activity = ?" +
                                                  " WHERE pan = ?;");

    public final SQLStmt insertActivity = new SQLStmt("INSERT INTO card_activity VALUES (?,?,?,?,?);");


    public long run( String        from_pan,
                     String        to_pan,
		     double        amount,
                     String        currency
		     ) throws VoltAbortException {

	long result = 0;

	voltQueueSQL(getAcct, EXPECT_ZERO_OR_ONE_ROW, from_pan);
	voltQueueSQL(getAcct, EXPECT_ZERO_OR_ONE_ROW, to_pan);

        // assume everything is good, eliminates a round-trip to the partitions
        voltQueueSQL(updateAcct,
                     -amount,
                     -amount,
                     getTransactionTime(),
                     from_pan
                     );

        voltQueueSQL(updateAcct,
                     amount,
                     amount,
                     getTransactionTime(),
                     to_pan
                     );

        voltQueueSQL(insertActivity,
                     from_pan,
                     getTransactionTime(),
                     "TRANSFER",
                     "D",
                     -amount
                     );

        voltQueueSQL(insertActivity,
                     to_pan,
                     getTransactionTime(),
                     "TRANSFER",
                     "C",
                     amount
                     );


	VoltTable accts[] = voltExecuteSQL(true);

	VoltTableRow from_acct = accts[0].fetchRow(0);
	int from_available = (int)from_acct.getLong(1);
        double from_balance = from_acct.getDouble(3);

	VoltTableRow to_acct = accts[1].fetchRow(0);
	int to_available = (int)to_acct.getLong(1);

        if (from_available == 0) {
            // card is not available for authorization or redemption
            throw new VoltAbortException("The transfer from card PAN " + from_pan + " was not available");
        }
        if (to_available == 0) {
            // card is not available for authorization or redemption
            throw new VoltAbortException("The transfer to card PAN " + to_pan + " was not available");
        }

        if (from_balance < amount) {
            // no balance available, so this will be declined
            throw new VoltAbortException("The transfer from card PAN " + from_pan + " rejected for insufficient balance");
        }

        return 1;
    }
}

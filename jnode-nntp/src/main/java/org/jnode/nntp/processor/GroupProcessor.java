package org.jnode.nntp.processor;

import org.jnode.nntp.DataProvider;
import org.jnode.nntp.FakeDataProvider;
import org.jnode.nntp.Processor;
import org.jnode.nntp.model.NntpResponse;

import java.util.Collection;
import java.util.LinkedList;

/**
 * TODO
 * <p/>
 * If the group is empty, one of the following three situations will
 * occur.  Clients MUST accept all three cases; servers MUST NOT
 * represent an empty group in any other way.
 * <p/>
 * o  The high water mark will be one less than the low water mark, and
 * the estimated article count will be zero.  Servers SHOULD use this
 * method to show an empty group.  This is the only time that the
 * high water mark can be less than the low water mark.
 * <p/>
 * o  All three numbers will be zero.
 * <p/>
 * o  The high water mark is greater than or equal to the low water
 * mark.  The estimated article count might be zero or non-zero; if
 * it is non-zero, the same requirements apply as for a non-empty
 * group.
 * <p/>
 * <p/>
 * If the group specified is not available on the server, a 411 response
 * MUST be returned.
 * <p/>
 * 411                           No such newsgroup
 */
public class GroupProcessor implements Processor {

    private DataProvider dataProvider = new FakeDataProvider();

    @Override
    public Collection<String> process(Collection<String> params) {

        Collection<String> response = new LinkedList<>();

            response.add("211 1 1 1 1");


        response.add(NntpResponse.END_OF_RESPONSE);

        return response;
    }
}

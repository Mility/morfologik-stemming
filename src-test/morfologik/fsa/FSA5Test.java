package morfologik.fsa;

import static morfologik.fsa.FSAFlags.NEXTBIT;
import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.junit.Test;

/**
 * Additional tests for {@link FSA5}.
 */
public final class FSA5Test {
	public ArrayList<String> expected = new ArrayList<String>(Arrays.asList(
	        "a", "aba", "ac", "b", "ba", "c"));
	public ArrayList<String> actual = new ArrayList<String>();

	@Test
	public void testVersion5() throws IOException {
		final FSA fsa = FSA.getInstance(this.getClass().getResourceAsStream("abc.fsa"));
		assertFalse(fsa.getFlags().contains(FSAFlags.NUMBERS));
		verifyContent(fsa);
	}

	@Test
	public void testVersion5WithNumbers() throws IOException {
		final FSA fsa = FSA.getInstance(this.getClass().getResourceAsStream("abc-numbers.fsa"));
		verifyContent(fsa);
		assertTrue(fsa.getFlags().contains(FSAFlags.NUMBERS));
	}

	@Test
	public void testArcsAndNodes() throws IOException {
		final FSA fsa1 = FSA.getInstance(this.getClass().getResourceAsStream(
		        "abc.fsa"));
		final FSA fsa2 = FSA.getInstance(this.getClass().getResourceAsStream(
		        "abc-numbers.fsa"));

		FSAInfo info1 = new FSAInfo(fsa1);
		FSAInfo info2 = new FSAInfo(fsa2);

		assertEquals(info1.arcsCount, info2.arcsCount);
		assertEquals(info1.nodeCount, info2.nodeCount);

		assertEquals(4, info2.nodeCount);
		assertEquals(6, info2.arcsCount);
	}

	@Test
	public void testNumbers() throws IOException {
		final FSA5 fsa = (FSA5) FSA.getInstance(
				this.getClass().getResourceAsStream("abc-numbers.fsa"));

		assertTrue(fsa.getFlags().contains(NEXTBIT));

		// Get all numbers for nodes.
		byte[] buffer = new byte[128];
		final ArrayList<String> result = new ArrayList<String>();
		walkNode(buffer, 0, fsa, fsa.getRootNode(), 0, result);

		Collections.sort(result);
		assertEquals(Arrays
		        .asList("0 c", "1 b", "2 ba", "3 a", "4 ac", "5 aba"), result);
	}

	private void walkNode(byte[] buffer, int depth, FSA5 fsa, int node,
	        int cnt, List<String> result) throws IOException {
		for (int arc = fsa.getFirstArc(node); arc != 0; arc = fsa.getNextArc(arc)) {
			buffer[depth] = fsa.getArcLabel(arc);

			if (fsa.isArcFinal(arc) || fsa.isArcTerminal(arc)) {
				result.add(cnt + " "
				        + new String(buffer, 0, depth + 1, "UTF-8"));
			}

			if (fsa.isArcFinal(arc))
				cnt++;

			if (!fsa.isArcTerminal(arc)) {
				walkNode(buffer, depth + 1, fsa, fsa.getEndNode(arc), cnt,
				        result);
				cnt += FSA5.decodeFromBytes(fsa.arcs, fsa.getEndNode(arc),
				        fsa.nodeDataLength);
			}
		}
	}

	private void verifyContent(FSA fsa) throws IOException {
		final FSATraversalHelper helper = new FSATraversalHelper(fsa);
		final Iterator<ByteBuffer> i = helper.getAllSubsequences(fsa.getRootNode());

		actual.clear();
		int count = 0;
		while (i.hasNext()) {
			ByteBuffer bb = i.next();
			assertEquals(0, bb.arrayOffset());
			assertEquals(0, bb.position());
			actual.add(new String(bb.array(), 0, bb.remaining(), "UTF-8"));
			count++;
		}
		assertEquals(expected.size(), count);
		Collections.sort(actual);
		assertEquals(expected, actual);
	}
}
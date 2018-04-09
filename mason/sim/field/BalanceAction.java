package sim.field;

import sim.util.IntHyperRect;

public class BalanceAction {
	int src, dst, dim, offset;

	public static final int size = 4;

	public BalanceAction() {
		this.offset = 0;
	}

	public BalanceAction(int src, int dst, int dim, int offset) {
		this.src = src;
		this.dst = dst;
		this.dim = dim;
		this.offset = offset;
	}

	public BalanceAction(int[] buf, int idx) {
		this(buf[idx], buf[idx + 1], buf[idx + 2], buf[idx + 3]);
	}

	public void writeToBuf(int[] buf, int idx) {
		buf[idx * size] = src;
		buf[idx * size + 1] = dst;
		buf[idx * size + 2] = dim;
		buf[idx * size + 3] = offset;
	}

	public static BalanceAction[] toActions(int[] buf) {
		if (buf.length % size != 0)
			throw new IllegalArgumentException("Incorrect input buffer length " + buf.length);

		BalanceAction[] ret = new BalanceAction[buf.length / size];

		for (int i = 0; i < buf.length; i += size)
			ret[i / size] = new BalanceAction(buf, i);

		return ret;
	}

	public int applyToPartition(DNonUniformPartition p) {
		if (offset == 0 || src == dst)
			return 0;

		IntHyperRect nsrcp = null, srcp = p.getPartition(src);
		IntHyperRect ndstp = null, dstp = p.getPartition(dst);

		int dir = srcp.ul.c[dim] < dstp.ul.c[dim] ? 1 : -1;

		try {
			nsrcp = srcp.resize(dim, dir, offset);
			ndstp = dstp.resize(dim, -dir, -offset);
		} catch (IllegalArgumentException e) {
			System.err.println("Illegal partition adjustment " + this + " - partition remain unchanged");
			return 0;
		}

		p.updatePartition(nsrcp);
		p.updatePartition(ndstp);

		return 1;
	}

	public String toString() {
		return String.format("BalanceAction [%d-(%d, %d)-%d] ", src, dim, offset, dst);
	}
}
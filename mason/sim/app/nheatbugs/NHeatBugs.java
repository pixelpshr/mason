/*
  Copyright 2006 by Sean Luke and George Mason University
  Licensed under the Academic Free License version 3.0
  See the file "LICENSE" for more information
*/

package sim.app.nheatbugs;

import sim.engine.*;
import sim.field.grid.*;
import sim.util.*;
import sim.field.*;

import mpi.*;
import java.nio.*;

import sim.util.Timing;
import sim.util.MPITest;

public class NHeatBugs extends SimState {
	private static final long serialVersionUID = 1;

	public double minIdealTemp = 17000;
	public double maxIdealTemp = 31000;
	public double minOutputHeat = 6000;
	public double maxOutputHeat = 10000;

	public double evaporationRate = 0.993;
	public double diffusionRate = 1.0;
	public static final double MAX_HEAT = 32000;
	public double randomMovementProbability = 0.1;

	public double getMinimumIdealTemperature() { return minIdealTemp; }
	public void setMinimumIdealTemperature( double temp ) { if ( temp <= maxIdealTemp ) minIdealTemp = temp; }
	public double getMaximumIdealTemperature() { return maxIdealTemp; }
	public void setMaximumIdealTemperature( double temp ) { if ( temp >= minIdealTemp ) maxIdealTemp = temp; }
	public double getMinimumOutputHeat() { return minOutputHeat; }
	public void setMinimumOutputHeat( double temp ) { if ( temp <= maxOutputHeat ) minOutputHeat = temp; }
	public double getMaximumOutputHeat() { return maxOutputHeat; }
	public void setMaximumOutputHeat( double temp ) { if ( temp >= minOutputHeat ) maxOutputHeat = temp; }
	public double getEvaporationConstant() { return evaporationRate; }
	public void setEvaporationConstant( double temp ) { if ( temp >= 0 && temp <= 1 ) evaporationRate = temp; }
	public Object domEvaporationConstant() { return new Interval(0.0, 1.0); }
	public double getDiffusionConstant() { return diffusionRate; }
	public void setDiffusionConstant( double temp ) { if ( temp >= 0 && temp <= 1 ) diffusionRate = temp; }
	public Object domDiffusionConstant() { return new Interval(0.0, 1.0); }
	public double getRandomMovementProbability() { return randomMovementProbability; }

	// public void setRandomMovementProbability( double t ) {
	// 	if (t >= 0 && t <= 1) {
	// 		randomMovementProbability = t;
	// 		for ( int i = 0 ; i < bugCount ; i++ )
	// 			if (bugs[i] != null)
	// 				bugs[i].setRandomMovementProbability( randomMovementProbability );
	// 	}
	// }
	public Object domRandomMovementProbability() { return new Interval(0.0, 1.0); }

	public double getMaximumHeat() { return MAX_HEAT; }

	public NDoubleGrid2D valgrid;
	public NDoubleGrid2D valgrid2;
	public NObjectGrid2D bugs;

	public DNonUniformPartition p;
	public DObjectMigratorNonUniform queue;

	public int bugCount, privBugCount;
	public int[] aoi;
	public IntHyperRect myPart;

	LoadBalancer lb;

	public NHeatBugs(long seed) {
		this(seed, 3000, 3000, 0, 2);
	}

	public NHeatBugs(long seed, int width, int height, int count, int aoi) {
		super(seed);

		bugCount = count;
		this.aoi = new int[] {aoi, aoi};

		try {
			p = DNonUniformPartition.getPartitionScheme(new int[] {width, height});
			//p.initUniformly(null);
			assert p.np == 2;
			p.insertPartition(new IntHyperRect(0, new IntPoint(0, 0), new IntPoint(3000, 2800)));
			p.insertPartition(new IntHyperRect(1, new IntPoint(0, 2800), new IntPoint(3000, 3000)));
			//p.insertPartition(new IntHyperRect(2, new IntPoint(0, 40), new IntPoint(1000, 60)));
			//p.insertPartition(new IntHyperRect(3, new IntPoint(0, 60), new IntPoint(1000, 1000)));
			p.commit();

			valgrid = new NDoubleGrid2D(p, this.aoi, 0);
			valgrid2 = new NDoubleGrid2D(p, this.aoi, 0);
			bugs = new NObjectGrid2D<NHeatBug>(p, this.aoi, s -> new NHeatBug[s], 64);

			queue = new DObjectMigratorNonUniform(p);

			privBugCount = bugCount / p.np;

			lb = new LoadBalancer(this.aoi, 500);
		} catch (Exception e) {
			e.printStackTrace(System.out);
			System.exit(-1);
		}

		myPart = p.getPartition();
	}

	public void start() {
		super.start();

		int[] size = myPart.getSize();

		for (int x = 0; x < privBugCount; x++) {
			double idealTemp = random.nextDouble() * (maxIdealTemp - minIdealTemp) + minIdealTemp;
			double heatOutput = random.nextDouble() * (maxOutputHeat - minOutputHeat) + minOutputHeat;
			int px, py;
			do {
				px = random.nextInt(size[0]) + myPart.ul.c[0];
				py = random.nextInt(size[1]) + myPart.ul.c[1];
			} while (bugs.get(px, py) != null);
			NHeatBug b = new NHeatBug(idealTemp, heatOutput, randomMovementProbability, px, py);
			bugs.set(px, py, b);
			schedule.scheduleOnce(b, 1);
		}

		//schedule.scheduleRepeating(Schedule.EPOCH, 0, new Inspector(), 500);
		schedule.scheduleRepeating(Schedule.EPOCH, 2, new Diffuser(), 1);
		schedule.scheduleRepeating(Schedule.EPOCH, 3, new Synchronizer(), 1);
	}

	public static void main(String[] args) throws MPIException {
		doLoopMPI(NHeatBugs.class, args);
		System.exit(0);
	}

	private class Synchronizer implements Steppable {
		private static final long serialVersionUID = 1;

		public void step(SimState state) {
			NHeatBugs hb = (NHeatBugs)state;

			Timing.start(Timing.MPI_SYNC_OVERHEAD);

			try {
				hb.valgrid.sync();
				hb.bugs.sync();
				hb.queue.sync();

				if (hb.lb.balance((int)hb.schedule.getSteps(), hb.valgrid.getRuntimes()) > 0) {
					myPart = p.getPartition();
					MPITest.execInOrder(x -> System.out.printf("[%d] Balanced at step %d new Partition %s\n", x, hb.schedule.getSteps(), p.getPartition()), 500);
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(-1);
			}
			for (Object obj : hb.queue) {
				privBugCount++;
				NHeatBug b = (NHeatBug)obj;
				bugs.set(b.loc_x, b.loc_y, b);
				schedule.scheduleOnce(b, 1);
			}
			hb.queue.objects.clear();

			Timing.stop(Timing.MPI_SYNC_OVERHEAD);
		}
	}

	private class Inspector implements Steppable {
		public void step( final SimState state ) {
			NHeatBugs hb = (NHeatBugs)state;
			//String s = String.format("PID %d Step %d Agent Count %d\n", hb.partition.pid, hb.schedule.getSteps(), hb.queue.size());
			//state.logger.info(String.format("PID %d Step %d Agent Count %d\n", hb.p.pid, hb.schedule.getSteps(), hb.privBugCount));
			if (DNonUniformPartition.getPartitionScheme().getPid() == 0) {
				state.logger.info("StepRuntime\t" + Timing.get(Timing.LB_RUNTIME) + "\n");
				state.logger.info("SyncRuntime\t" + Timing.get(Timing.MPI_SYNC_OVERHEAD) + "\n");
				state.logger.info("LBRuntime\t" + Timing.get(Timing.LB_OVERHEAD) + "\n");
			}
			// for (Steppable i : hb.queue) {
			// 	NHeatBug a = (NHeatBug)i;
			// 	s += a.toString() + "\n";
			// }
			//System.out.print(s);
		}
	}
}
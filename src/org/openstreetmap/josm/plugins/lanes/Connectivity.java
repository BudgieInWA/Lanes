package org.openstreetmap.josm.plugins.lanes;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Pair;

import java.util.*;

interface Connectivity {
    List<LaneRef> getConnections(LaneRef laneRef);
}

abstract class NodeConnectivity implements Connectivity {
    protected final Node _node;

    protected NodeConnectivity(Node node) {
        _node = node;
    }

    static NodeConnectivity create(Node node, LaneMappingMode m) {
        try {
            List<WayVector> wayVectors = Utils.getWaysFromNode(node, m);

            // Count lanes to determine if this is a basic lane divergence.
            // TODO: Check connectivity relations if they are present.
            List<Integer> inWays = new ArrayList<>();
            List<Integer> outWays = new ArrayList<>();
            List<Integer> inOutWays = new ArrayList<>();
            int inLanes = 0;
            int outLanes = 0;
            for (int i = 0; i < wayVectors.size(); i++) {
                WayVector wv = wayVectors.get(i);
                Way w = wv.getParent();
                MarkedRoadRenderer mrr = (MarkedRoadRenderer) m.wayIdToRSR.get(w.getUniqueId());
                if (mrr.getLaneCount(0) != 0) return null; // Don't support both way lanes
                int in = mrr.getLaneCount(wv.isForward() ? -1 : 1);
                int out = mrr.getLaneCount(wv.isForward() ? 1 : -1);
                if (in > 0 && out > 0) {
                    inOutWays.add(i);
                } else if (in > 0) {
                    inWays.add(i);
                    inLanes += in;
                } else if (out > 0) {
                    outWays.add(i);
                    outLanes += out;
                }
            }
            if (inLanes == 0 || outLanes == 0) return null;

            if (inLanes == outLanes && inOutWays.size() == 0 && (inWays.size() == 1 || outWays.size() == 1)) {
                // One way fork or merge.
                int mainI;
                List<Integer> divergingWays;
                if (inWays.size() == 1) {
                    mainI = inWays.get(0);
                    divergingWays = outWays;
                } else {
                    mainI = outWays.get(0);
                    divergingWays = inWays;
                }
                WayVector mainWv = wayVectors.get(mainI);

                // Because all ways are 1 way and the lane counts match up, the in Way ends here and the out Ways start here.
                RoadSplit output = new RoadSplit(mainWv.getParent(), !mainWv.isForward());

                // Default connectivity: Allocate the diverged ways to lanes to those in the main road in clockwise order.
                int lanesAllocated = 0;
                for (int i = 1; i < wayVectors.size(); i++) {
                    int j = (i + mainI) % wayVectors.size();
                    if (divergingWays.contains(j)) {
                        WayVector joiningWv = wayVectors.get(j);
                        MarkedRoadRenderer joiningMrr = (MarkedRoadRenderer) m.wayIdToRSR.get(joiningWv.getParent().getUniqueId());
                        int lanes = joiningMrr.getLaneCount(1);
                        output.addDivergingWay(joiningWv.getParent(), 1 + (output.isFork ? lanesAllocated : outLanes - lanes - lanesAllocated));
                        lanesAllocated += lanes;
                    }
                }
                if (lanesAllocated != inLanes) System.out.println("Mistake in associating 1-in-many-out lanes!");
                return output;
            }

            // TODO same thing but with two way roads.
            if (inOutWays.size() == 1) {
                int mainI = inOutWays.get(0);
                WayVector mainWv = wayVectors.get(mainI);
                MarkedRoadRenderer mrr = (MarkedRoadRenderer) m.wayIdToRSR.get(mainWv.getParent().getUniqueId());
                if (mrr.getLaneCount(mainWv.isForward() ? 1 : -1) != inLanes ||
                        mrr.getLaneCount(mainWv.isForward() ? -1 : 1) != outLanes) {
                    return null; // TODO support this case, because it is easy to allocate lanes starting at the center line.
                }

                RoadSplit output = new RoadSplit(mainWv.getParent(), !mainWv.isForward());

                // Default connectivity: Allocate the diverged ways to lanes to those in the main road in clockwise order.
                int lanesAllocated = 0;
                for (int i = 1; i < wayVectors.size(); i++) {
                    int j = (i + mainI) % wayVectors.size();
                    // TODO this assumes left hand roads, so out lanes come first.
                    if (lanesAllocated < outLanes && outWays.contains(j)) {
                        // Allocate an out way.
                        WayVector joiningWv = wayVectors.get(j);
                        MarkedRoadRenderer joiningMrr = (MarkedRoadRenderer) m.wayIdToRSR.get(joiningWv.getParent().getUniqueId());
                        int lanes = joiningMrr.getLaneCount(1);
                        output.addConnection(
                                new LaneRef(output.mainRoad, output.isFork ? 1 : -1, lanesAllocated + 1),
                                new LaneRef(joiningWv.getParent(), 1, 1));
                        lanesAllocated += lanes;
                    } else if (lanesAllocated >= outLanes && inWays.contains(j)) {
                        // Allocate an in way.
                        WayVector joiningWv = wayVectors.get(j);
                        MarkedRoadRenderer joiningMrr = (MarkedRoadRenderer) m.wayIdToRSR.get(joiningWv.getParent().getUniqueId());
                        int lanes = joiningMrr.getLaneCount(1);
                        output.addConnection(
                                new LaneRef(output.mainRoad, output.isFork ? -1 : 1, inLanes + outLanes - lanesAllocated - lanes + 1),
                                new LaneRef(joiningWv.getParent(), 1, 1));
                        lanesAllocated += lanes;
                    } else {
                        return null; // We got ways out of order.
                    }
                }
                // TODO check that the angle between the first in way and last out way is smaller than ~90 degrees so we can assume no connectivity between them.
                if (lanesAllocated != inLanes + outLanes) System.out.println("Mistake in associating road split lanes!");
                return output;
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }
}


/**
 * A one-way road that forks (from 1 Way into many) or merges (from many ways into 1).
 * A two-way road that forks into one-way ways.
 */
class RoadSplit extends NodeConnectivity {
    /** The road that determines the position of the other lanes. */
    public final Way mainRoad;
    /** Is a fork or a merge. */
    public final boolean isFork;

    public final List<Pair<LaneRef, LaneRef>> connections = new ArrayList<>();

    public RoadSplit(Way mainRoad, boolean isFork) {
        super(mainRoad.getNode(isFork ? mainRoad.getNodesCount() - 1 : 0));
        this.mainRoad = mainRoad;
        this.isFork = isFork;
    }

//    /** Get the ways that join to the main way. */
//    public List<> getOpposingWays() { }

    public void addDivergingWay(Way way, int leftmostLane) {
        connections.add(new Pair<>(
                new LaneRef(mainRoad, 1, leftmostLane),
                new LaneRef(way, 1, 1)
        ));
    }
//    public void addReverseDivergingWay(Way way, int leftmostLane) {}

    public void addConnection(LaneRef mainLane, LaneRef divergingLane) {
        connections.add(new Pair<>(mainLane, divergingLane));
    }

    @Override
    public List<LaneRef> getConnections(LaneRef laneRef) {
        List<LaneRef> output = new ArrayList<>();
        for (Pair<LaneRef, LaneRef> connection : connections) {
            if (laneRef.equals(connection.a)) {
                output.add(connection.b);
            } else if (laneRef.equals(connection.b)) {
                output.add(connection.a);
            }
        }
        return output;
    }
}

//TODO getRightOfWay hasRightOfWay

class LaneRef {
    // TODO use WayVectors inwards from the main road, outwards for connections to it.
    // TODO use signed lane numbers counted from the middle: 1 is the innermost forward lane, increasing outwards, negative for backwards.
    public final Way way;
    public final int direction;
    public final int lane;

    LaneRef(Way way, int direction, int lane) {
        this.way = way;
        this.direction = direction;
        this.lane = lane;
    }

    public LaneRef withLane(int lane) {
        return new LaneRef(way, direction, lane);
    }

    public boolean equals(LaneRef other) {
        return way.equals(other.way) && direction == other.direction && lane == other.lane;
    }

    public boolean equals(Object other) {
        if (!(other instanceof LaneRef)) return false;
        return equals((LaneRef) other);
    }

}
package org.openstreetmap.josm.plugins.lanes;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;

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
            int inLanes = 0;
            int outLanes = 0;
            for (int i = 0; i < wayVectors.size(); i++) {
                WayVector wv = wayVectors.get(i);
                Way w = wv.getParent();
                MarkedRoadRenderer mrr = (MarkedRoadRenderer) m.wayIdToRSR.get(w.getUniqueId());
                if (mrr.getLaneCount(0) != 0) return null; // Don't support both way lanes
                int in = mrr.getLaneCount(wv.isForward() ? -1 : 1);
                int out = mrr.getLaneCount(wv.isForward() ? 1 : -1);
                if (in > 0 && out > 0) return null; // Don't support intersections with two way roads (yet).
                if (in > 0) {
                    inWays.add(i);
                    inLanes += in;
                } else if (out > 0) {
                    outWays.add(i);
                    outLanes += out;
                }
            }
            if (inLanes == 0 || outLanes == 0) return null;
            if (inLanes != outLanes) return null;

            // Create the connectivity by matching up the lanes.
            if (inWays.size() == 1 || outWays.size() == 1) {
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
                OneWayLaneDivergence output = new OneWayLaneDivergence(mainWv.getParent(), !mainWv.isForward());

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

            return null;
        } catch (Exception e) {
            return null;
        }
    }
}


/**
 * When a one-way road forks (from 1 Way into many) or merges (from many ways into 1).
 */
class OneWayLaneDivergence extends NodeConnectivity {
    /** The road that determines the position of the other lanes. */
    public final Way mainRoad;
    /** Is a fork or a merge. */
    public final boolean isFork;

    public final Map<Long, LaneRef> divergingWayIdToLeftmostLane = new HashMap<>();

    public OneWayLaneDivergence(Way mainRoad, boolean isFork) {
        super(mainRoad.getNode(isFork ? mainRoad.getNodesCount() - 1 : 0));
        this.mainRoad = mainRoad;
        this.isFork = isFork;
    }

    public void addDivergingWay(Way way, int leftmostLane) {
        divergingWayIdToLeftmostLane.put(way.getUniqueId(), new LaneRef(way, 1, leftmostLane));
    }

    @Override
    public List<LaneRef> getConnections(LaneRef laneRef) {
        if (laneRef.way.equals(mainRoad)) throw new UnsupportedOperationException("Not yet implemented");
        LaneRef l = divergingWayIdToLeftmostLane.get(laneRef.way.getUniqueId());
        return Collections.singletonList(l.withLane(laneRef.lane - 1));
    }
}

class LaneRef {
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
}
package org.openstreetmap.josm.plugins.lanes;

import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Pair;
import org.openstreetmap.josm.tools.RightAndLefthandTraffic;

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

                RoadSplit output = new RoadSplit(wayVectors.get(mainI).reverse());
                int mainLanes = inLanes;

                // Default connectivity: Allocate the diverged ways to lanes to those in the main road in clockwise order.
                int lanesAllocated = 0;
                for (int i = 1; i < wayVectors.size(); i++) {
                    int j = (i + mainI) % wayVectors.size();
                    if (divergingWays.contains(j)) {
                        WayVector connectedWV = wayVectors.get(j);
                        MarkedRoadRenderer connectedMRR = (MarkedRoadRenderer) m.wayIdToRSR.get(connectedWV.getParent().getUniqueId());
                        int connectedLanes = connectedMRR.getLaneCount(1);
                        int lane = lanesAllocated + 1;
                        if (!Utils.isRightHand(connectedWV.getParent()) ^ !output.mainRoad.isForward()) {
                            // Allocating lanes outside in.
                             lane = mainLanes - lane + 1 - connectedLanes + 1;
                        }
                        output.addConnectedWay(connectedWV, lane * (output.mainRoad.isForward() ? 1 : -1));
                        lanesAllocated += connectedLanes;
                    }
                }
                if (lanesAllocated != inLanes) System.out.println("Mistake in associating 1-in-many-out lanes!");
                return output;
            }

            // TODO same thing but with two way roads.
            if (inOutWays.size() == 1) {
                int mainI = inOutWays.get(0);
                WayVector mainWv = wayVectors.get(mainI).reverse();
                MarkedRoadRenderer mrr = (MarkedRoadRenderer) m.wayIdToRSR.get(mainWv.getParent().getUniqueId());

                if (mrr.getLaneCount(mainWv.isForward() ? 1 : -1) != inLanes ||
                        mrr.getLaneCount(mainWv.isForward() ? -1 : 1) != outLanes) {
                    return null; // TODO support this case, because it is easy to allocate lanes starting at the center line.
                }

                RoadSplit output = new RoadSplit(mainWv);
                boolean isRightHand = Utils.isRightHand(mainWv.getParent());
                int leftHandLanes = isRightHand ? inLanes : outLanes;

                // Default connectivity: Allocate the diverged ways to lanes to those in the main road in clockwise order.
                int lanesAllocated = 0;
                for (int i = 1; i < wayVectors.size(); i++) {
                    int j = (i + mainI) % wayVectors.size();

                    if (lanesAllocated < leftHandLanes) {
                        WayVector connectedWv = wayVectors.get(j);
                        MarkedRoadRenderer connectedMrr = (MarkedRoadRenderer) m.wayIdToRSR.get(connectedWv.getParent().getUniqueId());
                        int connectedLaneDir = (isRightHand ? -1 : 1) * (connectedWv.isForward() ? 1 : -1);
                        int connectedLanes = connectedMrr.getLaneCount(connectedLaneDir);

                        // Allocating lanes outside in.
                        int lane = lanesAllocated + 1;
                        lane = leftHandLanes - lane + 1 - connectedLanes + 1;
                        output.addConnectedWay(connectedWv, lane * (isRightHand ? -1 : 1));
                        lanesAllocated += connectedLanes;

                        if (connectedMrr.getLaneCount(-1 * connectedLaneDir) > 0) {
                            // TODO these lanes can be allocated to the other direction. i--; continue;
                            return null;
                        }
                    } else if (lanesAllocated >= leftHandLanes) {
                        WayVector connectedWv = wayVectors.get(j);
                        MarkedRoadRenderer connectedMrr = (MarkedRoadRenderer) m.wayIdToRSR.get(connectedWv.getParent().getUniqueId());
                        int connectedLaneDir = (isRightHand ? -1 : 1) * (connectedWv.isForward() ? -1 : 1);
                        int connectedLanes = connectedMrr.getLaneCount(connectedLaneDir);

                        // Allocate lanes inside out.
                        int lane = lanesAllocated - leftHandLanes + 1;
                        output.addConnectedWay(connectedWv, lane * (isRightHand ? 1 : -1));
                        lanesAllocated += connectedLanes;

                        if (connectedMrr.getLaneCount(-1 * connectedLaneDir) > 0) {
                            return null; // Found more left-hand lanes that don't match up.
                        }
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
    /** Inward pointing vector of the road that determines the position of the other lanes. */
    public final WayVector mainRoad;

    public final Map<Integer, LaneRef> innerLaneToConnectedLane = new HashMap<>();

    public RoadSplit(WayVector mainRoadInward) {
        super(mainRoadInward.getParent().getNode(mainRoadInward.getTo()));
        this.mainRoad = mainRoadInward;
    }

//    /** Get the ways that join to the main way. */
//    public List<> getOpposingWays() { }

    public void addConnectedWay(WayVector connectedRoadOutward, int innermostMainRoadDirectedLane) {
        innerLaneToConnectedLane.put(innermostMainRoadDirectedLane, new LaneRef(connectedRoadOutward, connectedRoadOutward.isForward() ? 1 : -1));
    }

    public Pair<LaneRef, LaneRef> getWayConnection(WayVector wayVec) {
        for (int mainLane : innerLaneToConnectedLane.keySet()) {
            LaneRef connectedLane = innerLaneToConnectedLane.get(mainLane);
            if (connectedLane.wayVec.equals(wayVec)) {
                //TODO fix when !mainRoad.isForward()
                return new Pair<>(new LaneRef(mainRoad,  mainLane), connectedLane);
            }
        }
        return null;
    }

    @Override
    public List<LaneRef> getConnections(LaneRef laneRef) {
        if (laneRef.wayVec.equals(mainRoad)) {
            if (laneRef.directedLane == 0) return Collections.singletonList(innerLaneToConnectedLane.get(0));
            // Currently assuming no lane merges and only storing the innermost lane of each connected way.
            else for (int l = laneRef.directedLane; l != 0; l -= Integer.signum(laneRef.directedLane)) {
                if (innerLaneToConnectedLane.containsKey(l)) {
                    return Collections.singletonList(innerLaneToConnectedLane.get(l).getAdjacent(laneRef.directedLane - l));
                }
            }
            return Collections.emptyList();
        }

        else for (int mainLane : innerLaneToConnectedLane.keySet()) {
            LaneRef connected = innerLaneToConnectedLane.get(mainLane);
            if (connected.wayVec.equals(laneRef.wayVec)) {
                return Collections.singletonList(new LaneRef(mainRoad,  mainLane + (laneRef.directedLane - connected.directedLane)));
            }
        }

        return Collections.emptyList();
    }

    /**
     * Calculates the placement offset distance for the end of this road based on the connectivity to - and placement of -
     * another road. This is how far the node would want to move to be correctly positioned for this way (given its placement).
     *
     * When a road splits with its lanes diverging, the shared node may lay outside of the lanes represented by this
     * Road. For this reason, we calculate this offset based on the placement and widths of the lane we are connected to.
     *
     * If this way has placement=transition, this value should be treated as transition:start (or :end). Otherwise only the
     * first segment (or thereabouts) should be offset (like the common case of a single lane breaking away from a multi lane road).
     */
    public double getPlacementOffset(WayVector connectedWayVector, LaneMappingMode _m) {
        try {

            MarkedRoadRenderer connectedRoad = (MarkedRoadRenderer) _m.wayIdToRSR.get(connectedWayVector.getParent().getUniqueId());
            String placement = connectedRoad.getPlacementTag(connectedWayVector.getFrom() == 0);
            if (placement == null) {
                // TODO fix for right hand drive ways
                int lanes = connectedRoad.getLaneCount(1);
                placement = (lanes % 2 == 1) ? "middle_of:" + (lanes / 2 + 1) + "f" : "right_of:" + (lanes / 2) + "f";
                // FIXME placement is actually in the center of the road, (which is not here if the lane widths differ).
            }
            String[] placementBits = placement.substring(0, placement.length() - 1).split(":");
            int directedPlacementLane = connectedRoad.calculateDirectedLane(
                    Integer.parseInt(placementBits[1]),
                    placement.charAt(placement.length() - 1) == 'f');
            LaneRef placementLane = new LaneRef(connectedWayVector, directedPlacementLane * (connectedWayVector.isForward() ? 1 : -1));
            LaneRef mainLane = getConnections(placementLane).get(0);

            MarkedRoadRenderer mainRoad = (MarkedRoadRenderer) _m.wayIdToRSR.get(mainLane.wayVec.getParent().getUniqueId());
            String placementOnMainRoad = placementBits[0] + ":" +
                    mainRoad.calculateLaneNumber(mainLane.directedLane, mainLane.wayVec.isForward()).a +
                    (!mainLane.wayVec.isForward() ^ mainLane.directedLane < 0 ? "b" : "f");

            return mainRoad.getPlacementOffsetFrom(placementOnMainRoad, mainLane.wayVec.getTo());
        } catch (Exception ignored) {}

        return 0;
    }
}

//TODO getRightOfWay hasRightOfWay

class LaneRef {

    public final WayVector wayVec;
    /** The lane counted from the middle of the road, forward lanes in the direction of the wayVec positive, backwards lanes negative. */
    public final int directedLane;

    public LaneRef(WayVector wayVec, int directedLane) {
        this.wayVec = wayVec;
        this.directedLane = directedLane;
    }

    public LaneRef getInsideLane() {
        return new LaneRef(wayVec, directedLane - Integer.signum(directedLane));
    }
    public LaneRef getOutsideLane() {
        return new LaneRef(wayVec, directedLane + Integer.signum(directedLane));
    }
    public LaneRef getAdjacent(int offset) {
        return new LaneRef(wayVec, directedLane + offset);
    }

    public boolean equals(LaneRef other) {
        return wayVec.equals(other.wayVec) && directedLane == other.directedLane;
    }

    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LaneRef)) return false;
        return equals((LaneRef) other);
    }

    public String toString() {
        return "Lane " + (directedLane >= 0 ? "+" : "") + directedLane + " looking " +
                (wayVec.isForward() ? "forward" : "backwards") + " along Way " + wayVec.getParent().getUniqueId();
    }
}
package org.openstreetmap.josm.plugins.lanes;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MapView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Tests {
    private MapView mv;
    private LaneMappingMode m;
    private boolean rightHandRoads;

    private List<String> failures;

    private LatLon start;

    public Tests(MapView mv, LaneMappingMode m, boolean rightHandRoads) {
        this.mv = mv;
        this.m = m;
        this.rightHandRoads = rightHandRoads;

        this.start = rightHandRoads ? new LatLon(41.14, -112.60) : new LatLon(-27.42, 137.27);
    }

    public void run() {
        failures = new ArrayList<>();

        try {
            testDirectedLane();
//            testCalculateExitLane();
        } catch (Exception e) {
            failures.add(e.toString());
        }

        // Print failures
        if (failures.isEmpty()) {
            System.out.println("All " + (rightHandRoads ? "right" : "left") + " hand road tests passed!");
        } else {
            System.out.println((rightHandRoads ? "Right" : "Left") + " hand road tests failed:");
        }

        for (String f : failures) {
            System.out.println("\t" + f);
        }
    }

    private Way makeRoadWay(int lanesForward, int lanesBackward) {
        return makeRoadWay(lanesForward, lanesBackward,
                new Node(start), new Node(Utils.getLatLonRelative(start, 12, 250.0)));
    }
    private Way makeRoadWay(int lanesForward, int lanesBackward, Node startNode, Node endNode) {
        int totalLanes = lanesBackward + lanesBackward;
        boolean oneWay = lanesBackward == 0;
        Way w = new Way();
        w.setNodes(List.of(startNode, endNode));
        w.setKeys(Map.of("lanes", "" + totalLanes));
        if (oneWay) {
            w.setKeys(Map.of("oneway", "yes"));
        } else {
            w.setKeys(Map.of("lanes:forward", "" + lanesForward, "lanes:backward", "" + lanesBackward));
        }
        return w;
    }

    private void testDirectedLane() {
        MarkedRoadRenderer f4b3 = new MarkedRoadRenderer(makeRoadWay(4, 3), mv, m);

        if (rightHandRoads) {
            // Lanes are inside out.
            expectEqual("directed lane for forward 1", f4b3.calculateDirectedLane(1, true), 1);
            expectEqual("directed lane for forward 2", f4b3.calculateDirectedLane(2, true), 2);
            expectEqual("directed lane for forward 4", f4b3.calculateDirectedLane(4, true), 4);
            // Backward lanes are negative.
            expectEqual("directed lane for backward 1", f4b3.calculateDirectedLane(1, false), -1);
            expectEqual("directed lane for backward 2", f4b3.calculateDirectedLane(2, false), -2);
            expectEqual("directed lane for backward 3", f4b3.calculateDirectedLane(3, false), -3);
        } else {
            // Lanes are outside in.
            expectEqual("directed lane for forward 1", f4b3.calculateDirectedLane(1, true), 4);
            expectEqual("directed lane for forward 2", f4b3.calculateDirectedLane(2, true), 3);
            expectEqual("directed lane for forward 4", f4b3.calculateDirectedLane(4, true), 1);
            // Backward lanes are negative.
            expectEqual("directed lane for backward 1", f4b3.calculateDirectedLane(1, false), -3);
            expectEqual("directed lane for backward 2", f4b3.calculateDirectedLane(2, false), -2);
            expectEqual("directed lane for backward 3", f4b3.calculateDirectedLane(3, false), -1);
        }
    }

    private void testCalculateExitLane() {
        Node intersectionNode = new Node(start);
        Node mainNode = new Node(Utils.getLatLonRelative(start, 0, 250.0));
        Node exitLaneNode = new Node(Utils.getLatLonRelative(start, rightHandRoads ? 210 : 150, 250.0));
        Node contLaneNode = new Node(Utils.getLatLonRelative(start, 180, 250.0));

        Way mainWay = makeRoadWay(3, 0, intersectionNode, mainNode);
        Way exitLaneWay = makeRoadWay(1, 0, mainNode, exitLaneNode);
        Way contLaneWay = makeRoadWay(2, 0, mainNode, contLaneNode);

        // TODO can't do this without the nodes being a part of the dataset :(
        new NodeIntersectionRenderer(intersectionNode, mv, m);
        RightOfWay row = RightOfWay.create(intersectionNode, m);
        expectEqual("the calculated main road", row.mainRoad, new WayVector(0, 1, mainWay));

        expectEqual("inner lane continues to cont lane",
                row.innerLaneToConnectedLane.getOrDefault(1, null),
                new LaneRef(new WayVector(0, 1, contLaneWay), 1));
        expectEqual("outer lane joins exit lane",
                row.innerLaneToConnectedLane.getOrDefault(3, null),
                new LaneRef(new WayVector(0, 1, exitLaneWay), 1));
    }

    private <T> void expectEqual(String name, T actual, T expected) {
        if (!actual.equals(expected)) {
            failures.add("Expected " + name + " to be " + expected + " but got " + actual + ".");
        }
    }
}

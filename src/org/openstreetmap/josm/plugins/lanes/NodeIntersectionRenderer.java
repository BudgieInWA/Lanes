package org.openstreetmap.josm.plugins.lanes;

import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.util.GuiHelper;

import java.awt.*;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.List;

/*
 * IntersectionRenderer - renders and edits Intersections.
 *
 * -> Uses bezier curves and nearby RoadRenderers to render a 2D area around the intersection.
 * -> Allows for connectivity relations to be viewed and edited using drag and drop pieces.
 */

public class NodeIntersectionRenderer extends IntersectionRenderer {

    private Node _node;

    public NodeIntersectionRenderer(Node n, MapView mv, LaneMappingMode m) {
        super(mv, m);
        _node = n;
        _trimWays = false; // Only multi intersections do this.
        _connectivity = calculateLaneSplit();
        createIntersectionLayout();
    }

    @Override
    public List<WayVector> waysClockwiseOrder() {
        return Utils.getWaysFromNode(_node, _m);
    }

    @Override
    public List<List<IntersectionGraphSegment>> getPerimeter() {
        List<List<IntersectionGraphSegment>> out = new ArrayList<>();
        for (int i = 0; i < _wayVectors.size(); i++) out.add(new ArrayList<>());
        return out;
    }

    /** Calculates if this intersection is actually just lanes forking or joining. */
    private OneWayLaneSplit calculateLaneSplit() {
        _wayVectors = waysClockwiseOrder();

        try {
            // Count lanes to determine if this is a basic lane split.
            // TODO: Check connectivity relations if they are present.
            List<Integer> inWays = new ArrayList<>();
            List<Integer> outWays = new ArrayList<>();
            int inLanes = 0;
            int outLanes = 0;
            for (int i = 0; i < _wayVectors.size(); i++) {
                WayVector wv = _wayVectors.get(i);
                Way w = wv.getParent();
                MarkedRoadRenderer mrr = (MarkedRoadRenderer) _m.wayIdToRSR.get(w.getUniqueId());
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

            // Match up the placement of the smaller ways
            OneWayLaneSplit output;

            // 1 road in, multiple out
            if (inWays.size() == 1) {
                int mainI = inWays.get(0);
                WayVector mainWv = _wayVectors.get(mainI);
                // Because all ways are 1 way and the lane counts match up, the in Way ends here and the out Ways start here.
                output = new OneWayLaneSplit(mainWv.getParent(), !mainWv.isForward());
                // Iterate joining ways in clockwise order, allocating their lanes to those in the main road.
                int nextMainLane = 1;
                for (int i = 0; i < _wayVectors.size(); i++) {
                    int j = (i + mainI) % _wayVectors.size();
                    if (outWays.contains(j)) {
                        WayVector joiningWv = _wayVectors.get(j);
                        MarkedRoadRenderer joiningMrr = (MarkedRoadRenderer) _m.wayIdToRSR.get(joiningWv.getParent().getUniqueId());
                        output.joiningWayToLeftmostLane.put(joiningWv.getParent().getUniqueId(), nextMainLane);
                        nextMainLane += joiningMrr.getLaneCount(1);
                    }
                }
                if (nextMainLane != inLanes + 1) System.out.println("Mistake in associating 1-in-many-out lanes!");
                return output;
            }

            // TODO multiple roads in, 1 out.

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public void updatePlacements() {
        //
    }

    @Override
    public LatLon getPos() {
        return _node.getCoor();
    }

    public Way getOutline() {
        return _outline;
    }

    public Way getLowResOutline() { return _lowResOutline; }

    public Node getNode() {
        return _node;
    }
}
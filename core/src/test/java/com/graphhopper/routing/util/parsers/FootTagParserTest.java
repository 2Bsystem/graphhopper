/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.util.parsers;

import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.conditional.DateRangeParser;
import com.graphhopper.routing.ev.*;
import com.graphhopper.routing.util.AccessFilter;
import com.graphhopper.routing.util.EncodingManager;
import com.graphhopper.routing.util.PriorityCode;
import com.graphhopper.storage.BaseGraph;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.util.*;
import org.junit.jupiter.api.Test;

import java.text.DateFormat;
import java.util.Date;

import static com.graphhopper.routing.util.parsers.FootAverageSpeedParser.MEAN_SPEED;
import static com.graphhopper.routing.util.parsers.FootAverageSpeedParser.SLOW_SPEED;
import static org.junit.jupiter.api.Assertions.*;

/**
 * @author Peter Karich
 */
public class FootTagParserTest {
    private final BooleanEncodedValue footAccessEnc = VehicleAccess.create("foot");
    private final DecimalEncodedValue footAvgSpeedEnc = VehicleSpeed.create("foot", 4, 1, false);
    private final DecimalEncodedValue footPriorityEnc = VehiclePriority.create("foot", 4, PriorityCode.getFactor(1), false);
    private final BooleanEncodedValue bikeAccessEnc = VehicleAccess.create("bike");
    private final DecimalEncodedValue bikeAvgSpeedEnc = VehicleSpeed.create("bike", 4, 2, false);
    private final BooleanEncodedValue carAccessEnc = VehicleAccess.create("car");
    private final DecimalEncodedValue carAvSpeedEnc = VehicleSpeed.create("car", 5, 5, false);
    private final EncodingManager encodingManager = EncodingManager.start()
            .add(footAccessEnc).add(footAvgSpeedEnc).add(footPriorityEnc).add(new EnumEncodedValue<>(FootNetwork.KEY, RouteNetwork.class))
            .add(bikeAccessEnc).add(bikeAvgSpeedEnc).add(new EnumEncodedValue<>(BikeNetwork.KEY, RouteNetwork.class))
            .add(carAccessEnc).add(carAvSpeedEnc)
            .build();
    private final FootAccessParser accessParser = new FootAccessParser(encodingManager, new PMap());
    private final FootAverageSpeedParser speedParser = new FootAverageSpeedParser(encodingManager, new PMap());
    private final FootPriorityParser prioParser = new FootPriorityParser(encodingManager, new PMap());

    public FootTagParserTest() {
        accessParser.init(new DateRangeParser());
    }

    @Test
    public void testGetSpeed() {
        IntsRef fl = encodingManager.createEdgeFlags();
        footAccessEnc.setBool(false, fl, true);
        footAccessEnc.setBool(true, fl, true);
        footAvgSpeedEnc.setDecimal(false, fl, 10);
        assertEquals(10, footAvgSpeedEnc.getDecimal(false, fl), 1e-1);
    }

    @Test
    public void testSteps() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("highway", "service");
        IntsRef flags = encodingManager.createEdgeFlags();
        speedParser.handleWayTags(flags, way);
        assertEquals(MEAN_SPEED, footAvgSpeedEnc.getDecimal(false, flags), 1e-1);

        way.setTag("highway", "steps");
        flags = encodingManager.createEdgeFlags();
        speedParser.handleWayTags(encodingManager.createEdgeFlags(), way);
        assertTrue(MEAN_SPEED > footAvgSpeedEnc.getDecimal(false, flags));
    }

    @Test
    public void testCombined() {
        BaseGraph g = new BaseGraph.Builder(encodingManager).create();
        EdgeIteratorState edge = g.edge(0, 1);
        edge.set(footAvgSpeedEnc, 10.0).set(footAccessEnc, true, true);
        edge.set(carAvSpeedEnc, 100.0).set(carAccessEnc, true, false);

        assertEquals(10, edge.get(footAvgSpeedEnc), 1e-1);
        assertTrue(edge.get(footAccessEnc));
        assertTrue(edge.getReverse(footAccessEnc));

        assertEquals(100, edge.get(carAvSpeedEnc), 1e-1);
        assertTrue(edge.get(carAccessEnc));
        assertFalse(edge.getReverse(carAccessEnc));

        IntsRef raw = encodingManager.createEdgeFlags();
        footAvgSpeedEnc.setDecimal(false, raw, 10);
        footAccessEnc.setBool(false, raw, true);
        footAccessEnc.setBool(true, raw, true);
        assertEquals(0, carAvSpeedEnc.getDecimal(false, raw), 1e-1);
    }

    @Test
    public void testGraph() {
        BaseGraph g = new BaseGraph.Builder(encodingManager).create();
        g.edge(0, 1).setDistance(10).set(footAvgSpeedEnc, 10.0).set(footAccessEnc, true, true);
        g.edge(0, 2).setDistance(10).set(footAvgSpeedEnc, 5.0).set(footAccessEnc, true, true);
        g.edge(1, 3).setDistance(10).set(footAvgSpeedEnc, 10.0).set(footAccessEnc, true, true);
        EdgeExplorer out = g.createEdgeExplorer(AccessFilter.outEdges(footAccessEnc));
        assertEquals(GHUtility.asSet(1, 2), GHUtility.getNeighbors(out.setBaseNode(0)));
        assertEquals(GHUtility.asSet(0, 3), GHUtility.getNeighbors(out.setBaseNode(1)));
        assertEquals(GHUtility.asSet(0), GHUtility.getNeighbors(out.setBaseNode(2)));
    }

    @Test
    public void testAccess() {
        ReaderWay way = new ReaderWay(1);

        way.setTag("highway", "motorway");
        way.setTag("sidewalk", "yes");
        assertTrue(accessParser.getAccess(way).isWay());
        way.setTag("sidewalk", "left");
        assertTrue(accessParser.getAccess(way).isWay());

        way.setTag("sidewalk", "none");
        assertTrue(accessParser.getAccess(way).canSkip());

        way.clearTags();
        way.setTag("highway", "tertiary");
        way.setTag("sidewalk", "left");
        way.setTag("access", "private");
        assertTrue(accessParser.getAccess(way).canSkip());
        way.clearTags();

        way.setTag("highway", "pedestrian");
        assertTrue(accessParser.getAccess(way).isWay());

        way.setTag("highway", "footway");
        assertTrue(accessParser.getAccess(way).isWay());

        way.setTag("highway", "platform");
        assertTrue(accessParser.getAccess(way).isWay());

        way.setTag("highway", "motorway");
        assertTrue(accessParser.getAccess(way).canSkip());

        way.setTag("highway", "path");
        assertTrue(accessParser.getAccess(way).isWay());

        way.setTag("bicycle", "official");
        assertTrue(accessParser.getAccess(way).isWay());
        way.setTag("foot", "no");
        assertTrue(accessParser.getAccess(way).canSkip());

        way.setTag("foot", "official");
        assertTrue(accessParser.getAccess(way).isWay());

        way.clearTags();
        way.setTag("highway", "service");
        way.setTag("access", "no");
        assertTrue(accessParser.getAccess(way).canSkip());
        way.setTag("foot", "yes");
        assertTrue(accessParser.getAccess(way).isWay());

        way.clearTags();
        way.setTag("highway", "service");
        way.setTag("vehicle", "no");
        assertTrue(accessParser.getAccess(way).isWay());
        way.setTag("foot", "no");
        assertTrue(accessParser.getAccess(way).canSkip());

        way.clearTags();
        way.setTag("highway", "tertiary");
        way.setTag("motorroad", "yes");
        assertTrue(accessParser.getAccess(way).canSkip());

        way.clearTags();
        way.setTag("highway", "cycleway");
        assertTrue(accessParser.getAccess(way).isWay());
        way.setTag("foot", "no");
        assertTrue(accessParser.getAccess(way).canSkip());
        way.setTag("access", "yes");
        assertTrue(accessParser.getAccess(way).canSkip());

        way.clearTags();
        way.setTag("highway", "service");
        way.setTag("foot", "yes");
        way.setTag("access", "no");
        assertTrue(accessParser.getAccess(way).isWay());

        way.clearTags();
        way.setTag("route", "ferry");
        assertTrue(accessParser.getAccess(way).isFerry());
        way.setTag("foot", "no");
        assertTrue(accessParser.getAccess(way).canSkip());

        // #1562, test if ferry route with foot
        way.clearTags();
        way.setTag("route", "ferry");
        way.setTag("foot", "yes");
        assertTrue(accessParser.getAccess(way).isFerry());

        way.setTag("foot", "designated");
        assertTrue(accessParser.getAccess(way).isFerry());

        way.setTag("foot", "official");
        assertTrue(accessParser.getAccess(way).isFerry());

        way.setTag("foot", "permissive");
        assertTrue(accessParser.getAccess(way).isFerry());

        way.setTag("foot", "no");
        assertTrue(accessParser.getAccess(way).canSkip());

        way.setTag("foot", "designated");
        way.setTag("access", "private");
        assertTrue(accessParser.getAccess(way).canSkip());

        DateFormat simpleDateFormat = Helper.createFormatter("yyyy MMM dd");

        way.clearTags();
        way.setTag("highway", "footway");
        way.setTag("access:conditional", "no @ (" + simpleDateFormat.format(new Date().getTime()) + ")");
        assertTrue(accessParser.getAccess(way).canSkip());

        way.setTag("foot", "yes"); // the conditional tag even overrules "yes"
        assertTrue(accessParser.getAccess(way).canSkip());

        way.clearTags();
        way.setTag("highway", "footway");
        way.setTag("access", "no");
        way.setTag("access:conditional", "yes @ (" + simpleDateFormat.format(new Date().getTime()) + ")");
        assertTrue(accessParser.getAccess(way).isWay());
    }

    @Test
    public void testRailPlatformIssue366() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("railway", "platform");
        IntsRef edgeFlags = encodingManager.createEdgeFlags();
        accessParser.handleWayTags(edgeFlags, way);
        speedParser.handleWayTags(edgeFlags, way);
        assertTrue(footAccessEnc.getBool(false, edgeFlags));
        assertEquals(5, footAvgSpeedEnc.getDecimal(false, edgeFlags));

        way.clearTags();
        way.setTag("highway", "track");
        way.setTag("railway", "platform");
        edgeFlags = encodingManager.createEdgeFlags();
        accessParser.handleWayTags(edgeFlags, way);
        speedParser.handleWayTags(edgeFlags, way);
        assertTrue(footAccessEnc.getBool(false, edgeFlags));
        assertEquals(5, footAvgSpeedEnc.getDecimal(false, edgeFlags));

        way.clearTags();
        // only tram, no highway => no access
        way.setTag("railway", "tram");
        edgeFlags = encodingManager.createEdgeFlags();
        accessParser.handleWayTags(edgeFlags, way);
        speedParser.handleWayTags(edgeFlags, way);
        assertFalse(footAccessEnc.getBool(false, edgeFlags));
        assertEquals(0, footAvgSpeedEnc.getDecimal(false, edgeFlags));
    }

    @Test
    public void testPier() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("man_made", "pier");
        IntsRef flags = encodingManager.createEdgeFlags();
        accessParser.handleWayTags(flags, way);
        speedParser.handleWayTags(flags, way);
        assertTrue(footAccessEnc.getBool(false, flags));
        assertEquals(5, footAvgSpeedEnc.getDecimal(false, flags));
    }

    @Test
    public void testOneway() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("highway", "path");
        way.setTag("foot:forward", "yes");
        IntsRef edgeFlags = encodingManager.createEdgeFlags();
        accessParser.handleWayTags(edgeFlags, way, null);
        assertTrue(footAccessEnc.getBool(false, edgeFlags));
        assertFalse(footAccessEnc.getBool(true, edgeFlags));

        way.clearTags();
        way.setTag("highway", "path");
        way.setTag("foot:backward", "yes");
        accessParser.handleWayTags(edgeFlags = encodingManager.createEdgeFlags(), way);
        assertFalse(footAccessEnc.getBool(false, edgeFlags));
        assertTrue(footAccessEnc.getBool(true, edgeFlags));
    }

    @Test
    public void testFerrySpeed() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("route", "ferry");
        way.setTag("duration:seconds", 1800L);
        way.setTag("edge_distance", 30000.0);
        way.setTag("speed_from_duration", 30 / 0.5);
        // the speed is truncated to maxspeed (=15)
        IntsRef edgeFlags = encodingManager.createEdgeFlags();
        speedParser.handleWayTags(edgeFlags, way);
        assertEquals(15, speedParser.getAverageSpeedEnc().getDecimal(false, edgeFlags));
    }

    @Test
    public void testMixSpeedAndSafe() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("highway", "motorway");
        IntsRef flags = encodingManager.createEdgeFlags();
        accessParser.handleWayTags(flags, way);
        assertFalse(footAccessEnc.getBool(false, flags));
        assertFalse(footAccessEnc.getBool(true, flags));
        assertEquals(0, footAvgSpeedEnc.getDecimal(false, flags));

        way.setTag("sidewalk", "yes");
        flags = encodingManager.createEdgeFlags();
        accessParser.handleWayTags(flags, way);
        speedParser.handleWayTags(flags, way);
        assertTrue(footAccessEnc.getBool(false, flags));
        assertTrue(footAccessEnc.getBool(true, flags));
        assertEquals(5, footAvgSpeedEnc.getDecimal(false, flags), 1e-1);

        way.clearTags();
        way.setTag("highway", "track");
        flags = encodingManager.createEdgeFlags();
        accessParser.handleWayTags(flags, way);
        speedParser.handleWayTags(flags, way);
        assertTrue(footAccessEnc.getBool(false, flags));
        assertTrue(footAccessEnc.getBool(true, flags));
        assertEquals(5, footAvgSpeedEnc.getDecimal(false, flags), 1e-1);
    }

    @Test
    public void testPriority() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("highway", "cycleway");
        assertEquals(PriorityCode.UNCHANGED.getValue(), prioParser.handlePriority(way, null));

        way.setTag("highway", "primary");
        assertEquals(PriorityCode.AVOID.getValue(), prioParser.handlePriority(way, null));

        way.setTag("highway", "track");
        way.setTag("bicycle", "official");
        assertEquals(PriorityCode.SLIGHT_AVOID.getValue(), prioParser.handlePriority(way, null));

        way.setTag("highway", "track");
        way.setTag("bicycle", "designated");
        assertEquals(PriorityCode.SLIGHT_AVOID.getValue(), prioParser.handlePriority(way, null));

        way.setTag("highway", "cycleway");
        way.setTag("bicycle", "designated");
        way.setTag("foot", "designated");
        assertEquals(PriorityCode.PREFER.getValue(), prioParser.handlePriority(way, null));

        way.clearTags();
        way.setTag("highway", "primary");
        way.setTag("sidewalk", "yes");
        assertEquals(PriorityCode.SLIGHT_AVOID.getValue(), prioParser.handlePriority(way, null));

        way.clearTags();
        way.setTag("highway", "cycleway");
        way.setTag("sidewalk", "no");
        assertEquals(PriorityCode.AVOID.getValue(), prioParser.handlePriority(way, null));

        way.clearTags();
        way.setTag("highway", "road");
        way.setTag("bicycle", "official");
        way.setTag("sidewalk", "no");
        assertEquals(PriorityCode.SLIGHT_AVOID.getValue(), prioParser.handlePriority(way, null));

        way.clearTags();
        way.setTag("highway", "trunk");
        way.setTag("sidewalk", "no");
        assertEquals(PriorityCode.VERY_BAD.getValue(), prioParser.handlePriority(way, null));
        way.setTag("sidewalk", "none");
        assertEquals(PriorityCode.VERY_BAD.getValue(), prioParser.handlePriority(way, null));

        way.clearTags();
        way.setTag("highway", "residential");
        way.setTag("sidewalk", "yes");
        assertEquals(PriorityCode.PREFER.getValue(), prioParser.handlePriority(way, null));
    }

    @Test
    public void testSlowHiking() {
        ReaderWay way = new ReaderWay(1);
        way.setTag("highway", "track");
        way.setTag("sac_scale", "hiking");
        IntsRef flags = encodingManager.createEdgeFlags();
        speedParser.handleWayTags(flags, way);
        assertEquals(MEAN_SPEED, footAvgSpeedEnc.getDecimal(false, flags), 1e-1);

        way.setTag("highway", "track");
        way.setTag("sac_scale", "mountain_hiking");
        flags = encodingManager.createEdgeFlags();
        speedParser.handleWayTags(flags, way);
        assertEquals(SLOW_SPEED, footAvgSpeedEnc.getDecimal(false, flags), 1e-1);
    }

    @Test
    public void testBarrierAccess() {
        // by default allow access through the gate for bike & foot!
        ReaderNode node = new ReaderNode(1, -1, -1);
        node.setTag("barrier", "gate");
        // no barrier!
        assertFalse(accessParser.isBarrier(node));

        node = new ReaderNode(1, -1, -1);
        node.setTag("barrier", "gate");
        node.setTag("access", "yes");
        // no barrier!
        assertFalse(accessParser.isBarrier(node));

        node = new ReaderNode(1, -1, -1);
        node.setTag("barrier", "gate");
        node.setTag("access", "no");
        // barrier!
        assertTrue(accessParser.isBarrier(node));

        node.setTag("bicycle", "yes");
        // no barrier!?
        // assertTrue(footEncoder.handleNodeTags(node) == false);

        node = new ReaderNode(1, -1, -1);
        node.setTag("barrier", "gate");
        node.setTag("access", "no");
        node.setTag("foot", "yes");
        // no barrier!
        assertFalse(accessParser.isBarrier(node));

        node.setTag("locked", "yes");
        // barrier!
        assertTrue(accessParser.isBarrier(node));

        node.clearTags();
        node.setTag("barrier", "yes");
        node.setTag("access", "no");
        assertTrue(accessParser.isBarrier(node));
    }

    @Test
    public void testChainBarrier() {
        // by default allow access through the gate for bike & foot!
        ReaderNode node = new ReaderNode(1, -1, -1);
        node.setTag("barrier", "chain");
        assertFalse(accessParser.isBarrier(node));
        node.setTag("foot", "no");
        assertTrue(accessParser.isBarrier(node));
    }

    @Test
    public void testFord() {
        // by default do not block access due to fords!
        ReaderNode node = new ReaderNode(1, -1, -1);
        node.setTag("ford", "no");
        assertFalse(accessParser.isBarrier(node));

        node = new ReaderNode(1, -1, -1);
        node.setTag("ford", "yes");
        assertFalse(accessParser.isBarrier(node));

        // barrier!
        node.setTag("foot", "no");
        assertTrue(accessParser.isBarrier(node));

        FootAccessParser blockFordsParser = new FootAccessParser(encodingManager, new PMap("block_fords=true"));
        node = new ReaderNode(1, -1, -1);
        node.setTag("ford", "no");
        assertFalse(blockFordsParser.isBarrier(node));

        node = new ReaderNode(1, -1, -1);
        node.setTag("ford", "yes");
        assertTrue(blockFordsParser.isBarrier(node));
    }

    @Test
    public void testBlockByDefault() {
        ReaderNode node = new ReaderNode(1, -1, -1);
        node.setTag("barrier", "gate");
        // potential barriers are no barrier by default
        assertFalse(accessParser.isBarrier(node));
        node.setTag("access", "no");
        assertTrue(accessParser.isBarrier(node));

        // absolute barriers always block
        node = new ReaderNode(1, -1, -1);
        node.setTag("barrier", "fence");
        assertTrue(accessParser.isBarrier(node));
        node.setTag("barrier", "fence");
        node.setTag("access", "yes");
        assertFalse(accessParser.isBarrier(node));

        // pass potential barriers per default (if no other access tag exists)
        node = new ReaderNode(1, -1, -1);
        node.setTag("barrier", "gate");
        assertFalse(accessParser.isBarrier(node));
        node.setTag("access", "yes");
        assertFalse(accessParser.isBarrier(node));

        node = new ReaderNode(1, -1, -1);
        node.setTag("barrier", "fence");
        assertTrue(accessParser.isBarrier(node));

        // don't block potential barriers: barrier:cattle_grid should not block here
        node = new ReaderNode(1, -1, -1);
        node.setTag("barrier", "cattle_grid");
        assertFalse(accessParser.isBarrier(node));
    }

    @Test
    public void maxSpeed() {
        DecimalEncodedValue speedEnc = new DecimalEncodedValueImpl("foot_speed", 4, 2, true);
        // The foot max speed is supposed to be 15km/h, but for speed_bits=4,speed_factor=2 as we use here 15 cannot
        // be stored. In fact, when we set the speed of an edge to 15 and call the getter afterwards we get a value of 16
        // because of the internal (scaled) integer representation:
        EncodingManager em = EncodingManager.start().add(speedEnc).build();
        BaseGraph graph = new BaseGraph.Builder(em).create();
        EdgeIteratorState edge = graph.edge(0, 1).setDistance(100).set(speedEnc, 15);
        assertEquals(16, edge.get(speedEnc));

        // ... because of this we have to make sure the max speed is set to a value that cannot be exceeded even when
        // such conversion occurs. in our case it must be 16 not 15!
        // note that this test made more sense when we used encoders that defined a max speed.
        assertEquals(16, speedEnc.getNextStorableValue(15));
    }
}

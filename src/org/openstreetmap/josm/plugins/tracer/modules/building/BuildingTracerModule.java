/**
 *  Tracer - plugin for JOSM
 *  Jan Bilak, Marian Kyral, Martin Svec
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along
 *  with this program; if not, write to the Free Software Foundation, Inc.,
 *  51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.openstreetmap.josm.plugins.tracer.modules.building;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.search.SearchCompiler;
import org.openstreetmap.josm.data.osm.search.SearchParseError;
import org.openstreetmap.josm.plugins.tracer.CombineTagsResolver;
import org.openstreetmap.josm.plugins.tracer.TracerModule;
import org.openstreetmap.josm.plugins.tracer.connectways.AreaBoundaryWayNodePredicate;
import org.openstreetmap.josm.plugins.tracer.connectways.AreaPredicate;
import org.openstreetmap.josm.plugins.tracer.connectways.ClipAreas;
import org.openstreetmap.josm.plugins.tracer.connectways.ClipAreasSettings;
import org.openstreetmap.josm.plugins.tracer.connectways.EdNode;
import org.openstreetmap.josm.plugins.tracer.connectways.EdNodeLogicalAndPredicate;
import org.openstreetmap.josm.plugins.tracer.connectways.EdObject;
import org.openstreetmap.josm.plugins.tracer.connectways.EdWay;
import org.openstreetmap.josm.plugins.tracer.connectways.ExcludeEdNodesPredicate;
import org.openstreetmap.josm.plugins.tracer.connectways.GeomDeviation;
import org.openstreetmap.josm.plugins.tracer.connectways.IDiscardableCutoffPredicate;
import org.openstreetmap.josm.plugins.tracer.connectways.IEdNodePredicate;
import org.openstreetmap.josm.plugins.tracer.connectways.IReuseNearNodePredicate;
import org.openstreetmap.josm.plugins.tracer.connectways.LatLonSize;
import org.openstreetmap.josm.plugins.tracer.connectways.MergeIdenticalWays;
import org.openstreetmap.josm.plugins.tracer.connectways.RemoveNeedlessNodes;
import org.openstreetmap.josm.plugins.tracer.connectways.RetraceUpdater;
import org.openstreetmap.josm.plugins.tracer.connectways.ReuseNearNodeMethod;
import org.openstreetmap.josm.plugins.tracer.connectways.WayEditor;
import org.openstreetmap.josm.plugins.tracer.modules.ruian.RuianRecord;
import static org.openstreetmap.josm.tools.I18n.tr;
import org.openstreetmap.josm.tools.Pair;

import org.openstreetmap.josm.plugins.tracer.TracerUtils;

public abstract class BuildingTracerModule extends TracerModule {

    private static final double oversizeInDataBoundsMeters = 2.0;
    private static final double automaticOsmDownloadMeters = 400.0;

    private static final GeomDeviation m_connectTolerance = new GeomDeviation (0.15, Math.PI / 50);
    private static final GeomDeviation m_removeNeedlesNodesTolerance = new GeomDeviation (0.10, Math.PI / 50);
    private static final double m_discardCutoffsPercent = 15.0;
    private static final double m_discardCutoffsPercentMaxSum = 30.0;
    private final ClipAreasSettings m_clipSettings =
        new ClipAreasSettings (m_connectTolerance, m_discardCutoffsPercent, new BuildingTracerModule.DiscardableBuildingCutoff());

    private static final String reuseExistingBuildingNodePattern =
        "(building=* -building=no -building=entrance)";

    private static final String retraceAreaPattern =
        "(building=* -building=no -building=entrance)";

    private static final String ruianSourcePattern =
        "(source=\"cuzk:ruian\")";

    private static final SearchCompiler.Match m_reuseExistingBuildingNodeMatch;
    private static final SearchCompiler.Match m_clipBuildingWayMatch;
    private static final SearchCompiler.Match m_mergeBuildingWayMatch;
    private static final SearchCompiler.Match m_retraceAreaMatch;
    private static final SearchCompiler.Match m_ruianSourceMatch;

    static {
        try {
            m_reuseExistingBuildingNodeMatch = SearchCompiler.compile(reuseExistingBuildingNodePattern);
            m_clipBuildingWayMatch = m_reuseExistingBuildingNodeMatch; // use the same
            m_mergeBuildingWayMatch = m_clipBuildingWayMatch; // use the same
            m_retraceAreaMatch = SearchCompiler.compile(retraceAreaPattern);
            m_ruianSourceMatch = SearchCompiler.compile(ruianSourcePattern);
        }
        catch (SearchParseError e) {
            throw new AssertionError(tr("Unable to compile pattern"));
        }
    }

    class DiscardableBuildingCutoff implements IDiscardableCutoffPredicate {

        @Override
        public boolean canSilentlyDiscard(EdWay way, double cutoffs_percent) {

            if (cutoffs_percent > m_discardCutoffsPercentMaxSum)
                return false;

            // can silently discard given building as a result of clipping/cutoff removal?
            Map<String, String> keys = way.getInterestingKeys();
            for (Map.Entry<String, String> entry: keys.entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if (k != null && v != null && !(k.equals("building") && v.equals("yes")))
                    return false;
            }
            return true;
        }
    }

    class ReuseBuildingNearNodes implements IReuseNearNodePredicate {

        // distance tolerancies are in meters
        private final double m_reuseNearNodesToleranceDefault = m_connectTolerance.distanceMeters();
        private final double m_reuseNearNodesToleranceNonRuian = 0.30;
        private final double m_reuseNearNodesToleranceRetracedNodes = 0.40;

        private final double m_lookupDistanceMeters;
        private final ReuseNearNodeMethod m_reuseMethod = ReuseNearNodeMethod.moveAndReuseNode;
        private final AreaBoundaryWayNodePredicate m_ruianArea = new AreaBoundaryWayNodePredicate(m_ruianSourceMatch);

        private final Set<EdNode> m_retracedNodes;

        ReuseBuildingNearNodes (Set<EdNode> retraced_nodes) {
            m_retracedNodes = retraced_nodes;
            m_lookupDistanceMeters = Math.max (Math.max(m_reuseNearNodesToleranceDefault, m_reuseNearNodesToleranceRetracedNodes), m_reuseNearNodesToleranceNonRuian);
        }

        @Override
        public ReuseNearNodeMethod reuseNearNode(EdNode node, EdNode near_node, double distance_meters) {

            boolean retraced = m_retracedNodes != null && m_retracedNodes.contains(near_node);
            boolean ruian = m_ruianArea.evaluate(near_node);

            // be more tolerant for nodes occurring in retraced building
            if (retraced && !ruian) {
                System.out.println("RNN: retraced, dist=" + Double.toString(distance_meters));
                if (distance_meters <= m_reuseNearNodesToleranceRetracedNodes)
                    return m_reuseMethod;
            }

            // be more tolerant for non-ruian buildings
            if (!ruian) {
                System.out.println("RNN: non-ruian, dist=" + Double.toString(distance_meters));
                if (distance_meters <= m_reuseNearNodesToleranceNonRuian)
                    return m_reuseMethod;
            }

            // use default tolerance for others
            System.out.println("RNN: default, dist=" + Double.toString(distance_meters));
            if (distance_meters <= m_reuseNearNodesToleranceDefault)
                return m_reuseMethod;

            return ReuseNearNodeMethod.dontReuseNode;
        }

        @Override
        public double lookupDistanceMeters() {
            return m_lookupDistanceMeters;
        }
    }


    protected abstract class BuildingTracerTask extends AbstractTracerTask {

        protected boolean m_performNearBuildingsEdit;

        public BuildingTracerTask (LatLon pos, boolean ctrl, boolean alt, boolean shift) {
            super (pos, ctrl, alt ,shift);

            this.m_performNearBuildingsEdit = !m_ctrl;
        }

        @Override
        protected EdObject createTracedPolygonImpl(WayEditor editor) {

            System.out.println("  " + getName() + " keys: " + getRecord().getKeys(m_alt));

            // Look for object to retrace
            EdObject retrace_object = null;
            if (m_performRetrace) {
                Pair<EdObject, Boolean> repl = getObjectToRetrace(editor, m_pos, m_retraceAreaMatch);
                retrace_object = repl.a;
                boolean ambiguous_retrace = repl.b;

                if (ambiguous_retrace) {
                    postTraceNotifications().add(tr("Multiple existing Ruian building polygons found, retrace is not possible."));
                    return null;
                }
            }

            // Only update tags, do not change geometry of the object
            if (m_updateTagsOnly) {

                // To update tags only we need an existing object to update
                if (retrace_object == null) {
                    postTraceNotifications().add(tr("No existing Ruian building polygon found, tags only update is not possible."));
                    return null;
                }

                // Tag object
                if (!tagTracedObject(retrace_object))
                    return null;

                return retrace_object;
            }

            // Update object geometry as well
            // Create traced object
            EdObject trobj = getRecord().createObject(editor);

            // Everything is inside DataSource bounds?
            if (!checkInsideDataSourceBounds(trobj, retrace_object)) {
                wayIsOutsideDownloadedAreaDialog();
                return null;
            }

            // Connect to near building polygons
            // (must be done before retrace updates, we want to use as much old nodes as possible)
            if (!m_performNearBuildingsEdit) {
                reuseExistingNodes(trobj);
            }
            else {
                reuseNearNodes(trobj, retrace_object);
            }

            // Update geometries of retraced object
            if (retrace_object != null) {
                RetraceUpdater retr = new RetraceUpdater(true, postTraceNotifications());
                trobj = retr.updateRetracedObjects(trobj, retrace_object);
                if (trobj == null)
                    return null;
            }

            // Connect to touching nodes of near buildings
            // (must be done after retrace updates, we don't want to connect to the old polygon)
            if (m_performNearBuildingsEdit) {
                connectExistingTouchingNodes(trobj);
            }

            // Tag object
            if (!tagTracedObject(trobj))
                return null;

            // Clip other areas
            if (m_performClipping) {
                // #### Now, it clips using only the outer way. Consider if multipolygon clip is necessary/useful.
                AreaPredicate filter = new AreaPredicate (m_clipBuildingWayMatch);
                ClipAreas clip = new ClipAreas(editor, m_clipSettings, postTraceNotifications());
                clip.clipAreas(getOuterWay(trobj), filter);

                // Remove needless nodes
                AreaPredicate remove_filter = new AreaPredicate (m_clipBuildingWayMatch);
                RemoveNeedlessNodes remover = new RemoveNeedlessNodes(remove_filter, m_removeNeedlesNodesTolerance, (Math.PI*2)/3);
                remover.removeNeedlessNodes(editor.getModifiedWays());
            }

            // Merge duplicate ways
            // (Note that outer way can be merged to another way too, so we must watch it.
            // Otherwise, trobj variable would refer to an unused way.)
            if (m_performWayMerging) {
                AreaPredicate merge_filter = new AreaPredicate (m_mergeBuildingWayMatch);
                MergeIdenticalWays merger = new MergeIdenticalWays(editor, merge_filter);
                EdWay outer_way = merger.mergeWays(editor.getModifiedWays(), true, getOuterWay(trobj));
                if (trobj.isWay()) {
                    trobj = outer_way;
                }
            }

            return trobj;
        }

        private boolean tagTracedObject (EdObject obj) {

            Map <String, String> old_keys = obj.getKeys();
            Map <String, String> new_keys = new HashMap <> (getRecord().getKeys(m_alt));

            String old_building_tag = old_keys.get("building");
            String new_building_tag = new_keys.get("building");

            // always silently replace building=yes with the new building tag
            if (new_building_tag != null && "yes".equals(old_building_tag))
                old_keys.put("building", new_building_tag);
            // always silently replace building=construction with the new building tag
            if (new_building_tag != null && "construction".equals(old_building_tag))
                old_keys.put("building", new_building_tag);
            // always keep old building tag if the new tag is building=yes
            else if (old_building_tag != null && "yes".equals(new_building_tag))
                new_keys.put("building", old_building_tag);
            // keep church/chapel tag if the new one is civic
            else if ("civic".equals(new_building_tag) &&
                      ("church".equals(old_building_tag) ||
                       "chapel".equals(old_building_tag) ||
                       "cathedral".equals(old_building_tag) ||
                       "synagogue".equals(old_building_tag) ||
                       "university".equals(old_building_tag) ||
                       "college".equals(old_building_tag) ||
                       "school".equals(old_building_tag) ||
                       "kindergarten".equals(old_building_tag) ||
                       "train_station".equals(old_building_tag) ||
                       "hotel".equals(old_building_tag) ||
                       "apartments".equals(old_building_tag) ||
                       "transportation".equals(old_building_tag) ||
                       "hospital".equals(old_building_tag) ||
                       "hall".equals(old_building_tag) ||
                       "public".equals(old_building_tag)
                      )
                    )
                new_keys.put("building", old_building_tag);
            // keep train_station
            else if ("transportation".equals(new_building_tag) && "train_station".equals(old_building_tag))
                new_keys.put("building", old_building_tag);
            // keep apartments, detached, house, terrace tag if the new one is residential
            else if ("residential".equals(new_building_tag) &&
                      ("apartments".equals(old_building_tag) ||
                       "detached".equals(old_building_tag) ||
                       "house".equals(old_building_tag) ||
                       "terrace".equals(old_building_tag)
                      )
                    )
                new_keys.put("building", old_building_tag);
            // ... add other known conflicting building tags that should be resolved automatically

            // silently discard old source=cuzk:km or source=ruian or source=bing tags
            if (new_keys.containsKey("source") && old_keys.containsKey("source") &&
                    (old_keys.get("source").startsWith("cuzk") ||
                    old_keys.get("source").equals("ruian") ||
                    old_keys.get("source").equals("bing")||
                    old_keys.get("source").startsWith("uhul")||
                    old_keys.get("source").startsWith("orto")
                    ))
                old_keys.put("source", new_keys.get("source"));

            // silently replace wrong ruian date format
            if (old_keys.containsKey("start_date") &&
                new_keys.containsKey("start_date") &&
                (TracerUtils.convertDate(old_keys.get("start_date")).equals(new_keys.get("start_date")) ||
                 old_keys.get("start_date").startsWith(new_keys.get("start_date"))))
                old_keys.put("start_date", new_keys.get("start_date"));

            // silently replace building:levels=0
            if (old_keys.containsKey("building:levels") &&
                new_keys.containsKey("building:levels") &&
                (old_keys.get("building:levels")).equals("0") &&
                 !new_keys.get("building:levels").equals("0"))
                old_keys.put("building:levels", new_keys.get("building:levels"));

            // silently replace building:flats=0
            if (old_keys.containsKey("building:flats") &&
                new_keys.containsKey("building:flats") &&
                (old_keys.get("building:flats")).equals("0") &&
                 !new_keys.get("building:flats").equals("0"))
                old_keys.put("building:flats", new_keys.get("building:flats"));


            // merge missing keys to avoid resolution dialog when there're no collisions
            for (Map.Entry<String, String> tag: old_keys.entrySet()) {
                if (!new_keys.containsKey(tag.getKey()))
                    new_keys.put(tag.getKey(), tag.getValue());
            }
            for (Map.Entry<String, String> tag: new_keys.entrySet()) {
                if (!old_keys.containsKey(tag.getKey()))
                    old_keys.put(tag.getKey(), tag.getValue());
            }

            // combine and resolve conflicting keys
            Map<String, String> result = CombineTagsResolver.launchIfNecessary(old_keys, new_keys);
            if (result == null)
                return false;

            obj.setKeys(result);
            return true;
        }

        private Pair<EdObject, Boolean> getObjectToRetrace(WayEditor editor, LatLon pos, SearchCompiler.Match retraceAreaMatch) {
            AreaPredicate filter = new AreaPredicate(retraceAreaMatch);
            Set<EdObject> areas = editor.useNonEditedAreasContainingPoint(pos, filter);

            // look for exact RUIAN ID in case of RUIAN buildings
            String ruianref = null;
            if (getRecord() instanceof RuianRecord) {
                ruianref = Long.toString(((RuianRecord)getRecord()).getBuildingID());
            }

            boolean multiple_areas = false;
            EdObject building_area = null;
            for (EdObject area: areas) {
                if (area.isWay())
                    System.out.println("Retrace candidate EdWay: " + Long.toString(area.getUniqueId()));
                else if (area.isMultipolygon())
                    System.out.println("Retrace candidate EdMultipolygon: " + Long.toString(area.getUniqueId()));

                String ref = area.get("ref:ruian:building");
                if (ref != null && ref.equals(ruianref)) // exact match ;)
                    return new Pair<>(area, false);

                if (building_area == null)
                    building_area = area;
                else
                    multiple_areas = true;
            }

            if (multiple_areas) {
                return new Pair<>(null, true);
            }

            if (building_area != null) {
                return new Pair<>(building_area, false);
            }

            return new Pair<>(null, false);
        }

        private void connectExistingTouchingNodes(EdObject obj) {
            IEdNodePredicate filter = reuseExistingNodesFilter(obj);
            obj.connectExistingTouchingNodes(m_connectTolerance, filter);
        }

        private void reuseExistingNodes(EdObject obj) {
            obj.reuseExistingNodes (reuseExistingNodesFilter(obj));
        }

        private void reuseNearNodes(EdObject obj, EdObject retrace_object) {
            Set<EdNode> retrace_nodes = null;
            if (retrace_object != null)
                retrace_nodes = retrace_object.getAllNodes();
            obj.reuseNearNodes (new ReuseBuildingNearNodes(retrace_nodes), reuseExistingNodesFilter(obj));
        }

        private IEdNodePredicate reuseExistingNodesFilter(EdObject obj) {
            // Setup filtes - include building nodes only, exclude all nodes of the object itself
            IEdNodePredicate nodes_filter = new AreaBoundaryWayNodePredicate(m_reuseExistingBuildingNodeMatch);
            IEdNodePredicate exclude_my_nodes = new ExcludeEdNodesPredicate(obj);
            return new EdNodeLogicalAndPredicate (exclude_my_nodes, nodes_filter);
        }

        private boolean checkInsideDataSourceBounds(EdObject new_object, EdObject retrace_object) {
            LatLonSize bounds_oversize = LatLonSize.get(new_object.getBBox(), oversizeInDataBoundsMeters);
            if (retrace_object != null && !retrace_object.isInsideDataSourceBounds(bounds_oversize))
                return false;
            return new_object.isInsideDataSourceBounds(bounds_oversize);
        }

        @Override
        protected LatLonSize getMissingAreaCheckExtraSize(LatLon pos) {
            return LatLonSize.get(pos, 3 * oversizeInDataBoundsMeters);
        }

        @Override
        protected double getAutomaticOsmDownloadMeters () {
            return automaticOsmDownloadMeters;
        }

    }
}

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

package org.openstreetmap.josm.plugins.tracer.connectways;

import java.util.List;

import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.search.SearchCompiler.Match;

public final class AreaBoundaryWayNodePredicate implements IEdNodePredicate {

    private final AreaPredicate m_areaFilter;

    public AreaBoundaryWayNodePredicate(Match filter) {
        m_areaFilter = new AreaPredicate(filter);
    }

    @Override
    public boolean evaluate(EdNode ednode) {
        List<EdWay> edways = ednode.getEditorReferrers(EdWay.class);
        for (EdWay way: edways) {
            if (m_areaFilter.evaluate(way))
                return true;
        }

        List<Way> ways = ednode.getExternalReferrers(Way.class);
        for (Way way: ways) {
            if (m_areaFilter.evaluate(way))
                return true;
        }
        return false;
    }

    @Override
    public boolean evaluate(Node node) {
        List<Way> ways = OsmPrimitive.getFilteredList(node.getReferrers(), Way.class);
        for (Way way: ways) {
            if (m_areaFilter.evaluate(way))
                return true;
        }
        return false;
    }
}


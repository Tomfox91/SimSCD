"use strict";


/*
 *
 *  Let updateLanes be the function to update every lane present in the state of the client.
 *  This follows the general d3 update pattern. Additional details are documented in the Relazione Finale
 *
 */
var updateLanes = (function() {

    var pending = [];
    var lanesCont = svg.select('#lanes');

    function tryRI(roadId) {
        var road = city.roads[roadId];

        if (road.hiddenDestination) {
            return false;
            console.warn(road);
        }

        var lanes = city.lanes[roadId].lanes;
        var slices = lanes.length - 1;
        var road = city.roads[roadId];
        var deltax = Math.abs(road.ap1.x - road.bp1.x);
        var deltay = Math.abs(road.ap1.y - road.bp1.y);
        var delta = (deltax > deltay) ? deltax : deltay;
        var k1 = deltax / lanes.length;
        var k2 = deltay / lanes.length;
        city.roads[roadId].k1 = k1;
        city.roads[roadId].k2 = k2;
        var vert = road.ap1.y !== road.ap2.y;

        for (var i = 0; i < slices; i++) {
            var scope = city.roads[roadId];
            var shiftx = (road.ap1.x > road.bp1.x) ? -k1 * (i + 1) : k1 * (i + 1);
            var shifty = (road.ap1.y > road.bp1.y) ? k2 * (i + 1) : -k2 * (i + 1);

            lanesCont.append("line")
                .attr("class", "lane corsia")
                .attr("stroke-dasharray", "0.1,0.1")
                .attr("stroke-width", .01)
                .attr("x1", scope.ap1.x + shiftx)
                .attr("x2", scope.ap2.x + shiftx)
                .attr("y1", scope.bp1.y + shifty)
                .attr("y2", scope.bp2.y + shifty)
                .attr('data-thingName', scope.from)
                .attr('data-contNameTwo', scope.to);
            
        }

        for (var i = 0; i < slices + 1; i++) {
            var scope = city.roads[roadId];
            var shiftx = (road.ap1.x > road.bp1.x) ? -k1 * (i + .5) : k1 * (i + .5);
            var shifty = (road.ap1.y > road.bp1.y) ? k2 * (i + .5) : -k2 * (i + .5);

            if (lanes[vert ? i : slices - i].isBusReserved) {
                lanesCont.append("line")
                    .attr("class", "lane corsiabus")
                    .attr("stroke-width", (k1 + k2) - .02)
                    .attr("x1", scope.ap1.x + shiftx)
                    .attr("x2", scope.ap2.x + shiftx)
                    .attr("y1", scope.bp1.y + shifty)
                    .attr("y2", scope.bp2.y + shifty)
                    .attr('data-thingName', scope.from)
                    .attr('data-contNameTwo', scope.to);
            }
        }

        return true;
    }

    return function(roadId) {

        if (!tryRI(roadId)) {
            pending.push(roadId);
        }

        pending = pending.filter(function(ri) {
            return !tryRI(ri);
        });
    }

}());
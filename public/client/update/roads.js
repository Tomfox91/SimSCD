"use strict";

/**
 * @brief Draws the road overlay given the road and its parent container
 * @param [in] JQueryObject parent the road DOM parent
 * @param [in] road road the road to be overlayed
 * @return JQueryObject the road overlay DOM object
 */
function createRoadOverlay(parent, road) {
    if (road.hiddenDestination) {
        return $();
    }

    var minx = Math.min(road.cp1.x, road.cp2.x);
    var maxx = Math.max(road.cp1.x, road.cp2.x);
    var miny = Math.min(road.cp1.y, road.cp2.y);
    var maxy = Math.max(road.cp1.y, road.cp2.y);

    var mintx = Math.min(road.cp1.x, road.cp2.x, road.dp1.x, road.dp2.x);
    var minty = Math.min(road.cp1.y, road.cp2.y, road.dp1.y, road.dp2.y);

    var vert = road.cp1.y !== road.cp2.y;

    return parent.append("rect")
        .attr("x", minx === mintx ? minx : minx - 0.5)
        .attr("y", vert ? miny : (miny === minty ? miny + 0.5 : miny - 1))
        .attr("width", maxx - minx === 0 ? 0.5 : maxx - minx)
        .attr("height", maxy - miny === 0 ? 0.5 : maxy - miny)
        .attr("data-contType", "roads")
        .attr("data-contName", road.name)
        .attr("data-thingName", road.from)
        .attr("data-contNameTwo", road.to);
}


/*
 *
 *  Let updateRoads be the function to update every road present in the state of the client.
 *  This function does NOT use d3, it is executed once after every area subscription
 *
 */
window.updateRoads = (function() {

    var pending = [];

    var sideCont = svg.select('g#sidewalks');
    var roadCont = svg.select('g#roads');
    var overlayCont = svg.select("#roadoverlay");
    var sideCont = svg.select("#sideConts");


    function tryRI(roadId) {
        var road = city.roads[roadId];

        var pts = roadCoordinates(road);

        if (!pts.p2) {
            if (typeof road !== 'undefined')
                road.hiddenDestination = 1;
            return false;
        }
        road.hiddenDestination = 0;
        var sx, sy;


        if (pts.p1.x > pts.p2.x)
            sy = -0.35;
        else if (pts.p1.x < pts.p2.x)
            sy = 0.35;
        else
            sy = 0;

        if (pts.p1.y > pts.p2.y)
            sx = 0.35;
        else if (pts.p1.y < pts.p2.y)
            sx = -0.35;
        else
            sx = 0;

        road.ap1 = {
            x: pts.p1.x + sx,
            y: pts.p1.y + sy
        };
        road.ap2 = {
            x: pts.p2.x + sx,
            y: pts.p2.y + sy
        };


        if (pts.p1.x > pts.p2.x)
            sy = -0.05;
        else if (pts.p1.x < pts.p2.x)
            sy = 0.05;
        else
            sy = 0;

        if (pts.p1.y > pts.p2.y)
            sx = 0.05;
        else if (pts.p1.y < pts.p2.y)
            sx = -0.05;
        else
            sx = 0;

        road.bp1 = {
            x: pts.p1.x + sx,
            y: pts.p1.y + sy
        };
        road.bp2 = {
            x: pts.p2.x + sx,
            y: pts.p2.y + sy
        };


        if (pts.p1.x > pts.p2.x)
            sy = 0.5;
        else if (pts.p1.x < pts.p2.x)
            sy = -0.5;
        else
            sy = 0;

        if (pts.p1.y > pts.p2.y)
            sx = 0.5;
        else if (pts.p1.y < pts.p2.y)
            sx = -0.5;
        else
            sx = 0;

        road.cp1 = {
            x: pts.p1.x + sx,
            y: pts.p1.y + sy
        };
        road.cp2 = {
            x: pts.p2.x + sx,
            y: pts.p2.y + sy
        };


        if (pts.p1.x > pts.p2.x)
            sy = -0.5;
        else if (pts.p1.x < pts.p2.x)
            sy = 0.5;
        else
            sy = 0;

        if (pts.p1.y > pts.p2.y)
            sx = -0.5;
        else if (pts.p1.y < pts.p2.y)
            sx = 0.5;
        else
            sx = 0;

        road.dp1 = {
            x: pts.p1.x + sx,
            y: pts.p1.y + sy
        };
        road.dp2 = {
            x: pts.p2.x + sx,
            y: pts.p2.y + sy
        };


        roadCont.append("line")
            .attr("class", "strada")
            .attr("x1", road.ap1.x)
            .attr("x2", road.ap2.x)
            .attr("y1", road.ap1.y)
            .attr("y2", road.ap2.y)
            .attr('data-contName', road.name)
            .attr("data-thingName", road.from)
            .attr("data-contNameTwo", road.to);

        roadCont.append("line")
            .attr("class", "strada")
            .attr("x1", road.bp1.x)
            .attr("x2", road.bp2.x)
            .attr("y1", road.bp1.y)
            .attr("y2", road.bp2.y)
            .attr('data-contName', road.name)
            .attr("data-thingName", road.from)
            .attr("data-contNameTwo", road.to);

        var vert = road.ap1.y !== road.ap2.y;
        var up = (road.ap1.x + road.ap1.y) < (road.ap2.x + road.ap2.y);
        var ss = {
            p1: vert ? road.cp1 : road.dp1,
            p2: vert ? road.cp2 : road.dp2
        };

        sideCont.append("line")
            .attr("class", "sidewalk")
            .attr("x1", ss.p1.x)
            .attr("x2", ss.p2.x)
            .attr("y1", ss.p1.y)
            .attr("y2", ss.p2.y)
            .attr('data-contName', road.name)
            .attr("data-thingName", road.from)
            .attr("data-contNameTwo", road.to);


        var ms = 1;
        var mss = .6;
        var as = .275;
        var r = .25;

        function addCircle(pname) {
            function c(vu, vd, hu, hd) {
                if (vert && up) return vu;
                if (vert && !up) return vd;
                if (!vert && up) return hu;
                if (!vert && !up) return hd;
            }

            var xy = {
                x: ss.p1.x + c(-as, +as, +ms, -ms),
                y: ss.p1.y + c(+ms, -ms, +as, -as)
            };
            road[pname] = xy;

            ms += mss;

            return sideCont.append("circle")
                .attr("cx", xy.x)
                .attr("cy", xy.y)
                .attr("r", r);
        }

        function addContainer(pname, clazz, suffix, contType) {
            var cname = road.name + suffix;
            var cname2 = road.to + suffix;
            var cname3 = road.from + suffix;

            addCircle(pname)
                .attr("class", clazz + " selectable")
                .attr("data-contType", contType)
                .attr("data-contNameTwo", cname2)
                .attr("data-thingName", cname3)
                .attr("data-contName", cname);
            city[contType][cname] = {
                name: cname,
                contained: []
            };
        }

        if (road.isBusStop) addContainer("pBusStop", "busStop", "", "busStops");
        if (road.hasPark) addContainer("pPark", "parkingLot", "/park", "parkings");
        addContainer("pBuild", "building", "/build", "buildings");


        createRoadOverlay(overlayCont, road)
            .attr("class", "roadoverlay selectable")
            .attr('fill-opacity', '0')
            .attr('stroke-opacity', '0');

        return true;
    }

    return function(msg) {
        msg.contained.forEach(function(road) {
            if (!tryRI(road.name) && pending.indexOf(road.name) == -1) {
                pending.push(road.name);
            }
        });

        pending = pending.filter(function(ri) {
            return !tryRI(ri);
        });
    }
}());
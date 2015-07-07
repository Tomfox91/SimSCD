"use strict";

!(function() {

    
/**
 * @brief returns a coordinate to move the pedestrian to when she enters in a specified cross
 * @param [in] cross target the target cross the pedestrians enters into
 * @param [in] N|S|E|W vertex the side of the cross
 * @param [in] x|y axis the axis we want to get the coordinate of
 * @param [in] pedestrian pedone the pedestrian we want to move
 * @return int the coordinate
 */
function pedEnterInCross(target, vertex, axis, pedone) {
    
    if (typeof pedone.cP === 'undefined')
        pedone.cP = { x: 0, y: 0};
    
    var pos = 0;
        
    if (axis == 'x') {
        switch (vertex.substr(1,1)) {
            case 'E':
                pos = target + 4/5*0.5;
                break;
            case 'W':
                pos = target - 4/5*0.5;
                break;
        }
    }
    else {
        switch (vertex.substr(0,1)) {
            case 'N':
                pos = target - 4/5*0.5;
                break;
            case 'S':
                pos = target + 4/5*0.5;
                break;
        }
    }
    pos += (getRandomSign()*Math.random() / 14);
    pedone.cP[axis] = pos;
    
    return pos;
};


/**
 * @brief returns the source and target crosses of the road the pedestrian is walking on
 * @param [in] pedestrian pedone the walker
 * @return Array containing source, respectively, target cross
 */
function rGetCrosses(pedone) {
    var sC = city.crossings[city.roads[justRoad(pedone.container.id)].from];
    var tC = city.crossings[city.roads[justRoad(pedone.container.id)].to];
    var side = pedone.container.id.substr(-1);

    if (side == 'r')
        return [tC, sC];
    else
        return [sC, tC];
}

/**
 * @brief Given two crosses and the side the pedestrian is walking along (left or right,
 * depending on the position of the crosses relative to each other), determines the side
 * of incumbence of the pedestrian
 * @param [in] cross sC the starting cross of the movement
 * @param [in] cross tC the target cross of the movement
 * @param [in] f|r side the front or reverse side, depending on if the pedestrian is
 *             keeping the right or the left of the road
 * @return String the vertex specified as [N|S][E|W]
 */
function rGetVertex(sC, tC, side) {
    
    if (typeof tC === 'undefined')
        tC = {pos: {x: 0, y:0}}
    
    var diffs = { 
        x: ((sC.pos.x - tC.pos.x) < 0) ? -1 : ((sC.pos.x - tC.pos.x) > 0) ? 1 : 0 , 
        y: ((sC.pos.y - tC.pos.y) < 0) ? -1 : ((sC.pos.y - tC.pos.y) > 0) ? 1 : 0 
    }; 
    var ns, ew;
    
    switch (diffs.x) {
        case -1:
            ew = 'E';
            ns = (side == 'r') ? 'N' : 'S';
            break;
        case 0:
            switch (diffs.y) {
                case -1:
                    ns = 'S';
                    ew = (side == 'r') ? 'E' : 'W';
                    break;
                case 1:
                    ns = 'N';
                    ew = (side == 'r') ? 'W' : 'E';
                    break;
            }
            break;
        case 1:
            ew = 'W';
            ns = (side == 'r') ? 'S' : 'N';
            break;
    }
    return ns+ew;
}

    
    /**
     * @brief Returns the specified coordinate the pedestrian moves to to enqueue herself at some entity
     * @param [in] pedestrian pedone the pedestrian
     * @param [in] x|y axis the axis we are interested in
     * @return int the coordinate at the specified axis we need to move the pedestrian to
     */
function rqueuePed(pedone, axis) {

    var sC, tC, c;
    c = rGetCrosses(pedone);
    sC = c[0];
    tC = c[1];
    
    var diffs = { 
        x: ((sC.pos.x - tC.pos.x) < 0) ? -1 : ((sC.pos.x - tC.pos.x) > 0) ? 1 : 0 , 
        y: ((sC.pos.y - tC.pos.y) < 0) ? -1 : ((sC.pos.y - tC.pos.y) > 0) ? 1 : 0 
    };

    var nQueue = pedone.enqueuedAt.pos || 0;    

    if (diffs[axis] !== 0)
        return tC.pos[axis] + diffs[axis]*(0.6 + nQueue * Math.random() / 14)
    else
        return pedone.cP[axis];
}

function pedToPoint(point, pedone, axis) {
    var road = city.roads[justRoad(pedone.container.id)];
    var startCross = city.crossings[road.from];
    var targetCross = city.crossings[road.to];
    if (startCross.pos[axis] !== targetCross.pos[axis]) {
        //TMP
        return road[point][axis];
    }
    else {
        //TMP
        return startCross.pos[axis];
    }
}


function pedInPoint(point, pedone, axis) {
    return city.roads[justRoad(pedone.container.id)][point][axis] + getRandomSign()*(Math.random() / 5);
}


/*
 *
 *  Let updatePedestrians be the function to update every pedestrian present in the state of the client.
 *  This follows the general d3 update pattern. Additional details are documented in the Relazione Finale
 *
 */
var pedestriansGroup = svg.select('#pedestrians');

window.updatePedestrians = function() {
    var datipedoni = d3.values(city.pedestrians);
    
    // 1 - Data join
    var pedone = pedestriansGroup.selectAll(".pedone")
    .data(datipedoni, function(d) { return d.thing.id; });

    // 4 - Delete selection
    pedone.exit()
    .remove();

    // 2 - Enter selection
    var pedoniEnter = pedone.enter().append("circle")
    .attr('class', 'pedone')
    .attr('r', 0.025);

    // 3 - Update selection
    pedone
    .filter(function(d) { return d.needsUpdate !== 0 && d.container.type == 'cross' && d.anim !== 1; })
    .transition()
    .ease('linear')
    .each('start', function(d) { d.anim = 1; return; })
    .duration(function(d) { return d.duration /4;})
    .attr('cx', function(d) { return pedEnterInCross(city.crossings[d.container.id].pos.x, d.vertex, 'x', d); })
    .attr('cy', function(d) { return pedEnterInCross(city.crossings[d.container.id].pos.y, d.vertex, 'y', d); })
    .each('end', function(d) { d.anim = 0; d.needsUpdate = 0; return; });

    pedone
    .filter(function(d) { return d.needsUpdate !== 0 && d.container.type == 'sidewalk' && d.anim !== 1; })
    .transition()
    .ease('linear')
    .each('start', function(d) { d.anim = 1; return; })
    .duration(function(d) { return d.duration * 2 / 10;})
    .attr('cx', function(d) { var c = rGetCrosses(d);
        return pedEnterInCross(c[0].pos.x, rGetVertex(c[0], c[1], getSide(d)), 'x', d); })
    .attr('cy', function(d) { var c = rGetCrosses(d);
        return pedEnterInCross(c[0].pos.y, rGetVertex(c[0], c[1], getSide(d)), 'y', d); })
    .transition()
    .duration(function(d) { return d.duration * 7 / 10;})
    .attr('cx', function(d) { return rqueuePed(d, 'x'); })
    .attr('cy', function(d) { return rqueuePed(d, 'y'); })
    .each('end', function(d) { d.anim = 0; d.needsUpdate = 0; return; });
    
    
    [{t:'busStop', p:'pBusStop'}, {t:'build', p:'pBuild'}].forEach(function(q) {
        pedone
        .filter(function(d) { return d.needsUpdate !== 0 && d.container.type === q.t && d.anim !== 1; })
        .transition()
        .ease('linear')
        .each('start', function(d) { d.anim = 1; })
        .duration(300)
        .attr('cx', function(d) { return pedToPoint(q.p, d, 'x'); })
        .attr('cy', function(d) { return pedToPoint(q.p, d, 'y'); })
        .transition()
        .ease('linear')
        .duration(300)
        .attr('cx', function(d) { return pedInPoint(q.p, d, 'x'); })
        .attr('cy', function(d) { return pedInPoint(q.p, d, 'y'); })
        .each('end', function(d) { d.anim = 0; d.needsUpdate = 0; });
    });

            // selected/unselected state
    pedone
    .filter(function(d) {return d.needsSelUpdate && d.selected === true;})
    .classed('selectedThing', true)
    .each(function(d) {d.needsSelUpdate = 0;});

    pedone
    .filter(function(d) {return d.needsSelUpdate && d.selected === false;})
    .classed('selectedThing', false)
    .each(function(d) {d.needsSelUpdate = 0;});
}

}());
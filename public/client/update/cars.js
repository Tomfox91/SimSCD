"use strict";

!(function() {

/**
 * @brief Calculates the position of a car when entering a cross, according to the entrance side
 * @param [in] int target a coordinate of the center of the cross, either X or Y
 * @param [in] int position the same axis as above coordinate of the car
 * @return int position to move to to enter in cross
 */
function enterInCross(target, position) {

    if (Math.abs(target - position) > 0.5) {
        if ( target < position ) 
            return target + 2/3*0.5;
        else
            return target - 2/3*0.5;
    }
    else {
        return position;
    }

};


/**
 * @brief Returns the position on the required axis of the car to make it position correctly
 * in an ordered queue of cars, according to its destination
 * @param [in] vehicle car the vehicle to calculate the enqueued position of
 * @param [in] x|y axis the desired axis
 * @return int the position on the required axis of the car in the queue relative to its destination
 */
function queueCar(car, axis) {

    var startCross = city.crossings[city.roads[justRoad(car.container.id)].from].pos[axis];
    var targetCross = city.crossings[city.roads[justRoad(car.container.id)].to].pos[axis];

    var otherAxis = (axis == 'x') ? 'y' : 'x';

    var shift;

    var nQueue = car.enqueuedAt.pos || 0;
        if (startCross > targetCross) {
            shift = 0.9;
            shift += nQueue * 0.3;
        }
        else if (startCross < targetCross) {
            shift = -0.9;
            shift -= nQueue * 0.3;
        }
        else
            shift = 0
        return targetCross + shift + carToLaneShift(car, axis);
}

    
/**
 * @brief Calculates the position on the required axis of the car to make it move
 * from its position to the lane it is actually travelling on
 * @param [in] vehicle car the vehicle to calculate the lane position it belongs into
 * @param [in] x|y axis the desired axis
 * @return the position on the required axis of the car to make it move to its lane
 */
function carToLane(car, axis) {
    var startCross = city.crossings[city.roads[justRoad(car.container.id)].from];

    return startCross.pos[axis] + carToLaneShift(car, axis);
}

    
/**
 * @brief Calculates the shift needed to display the car in its own lane, rather than
 * in the middle of the road it is travelling on
 * @param [in] vehicle car the vehicle to calculate the lane position it belongs into
 * @param [in] x|y axis the desired axis
 * @return the position on the required axis of the car to make it move to its lane
 */
function carToLaneShift(car, axis) {
    var road = city.roads[justRoad(car.container.id)];
    var startCross = city.crossings[road.from];
    var targetCross = city.crossings[road.to];

    var otherAxis = (axis == 'x') ? 'y' : 'x';

    var shift;
    var i = keepLane(car.container.id);

    if (startCross.pos[axis] > targetCross.pos[axis]) {
        shift = -0.3;
        shift += road.k1*i;
    }
    else if (startCross.pos[axis] < targetCross.pos[axis]) {
        shift = +0.3;
        shift -= road.k1*i;
    }
    else {
        //remember we're in the +/- plane
        if (axis == 'y') {
            if (startCross.pos[otherAxis] > targetCross.pos[otherAxis]) {
                shift = -0.3;
                shift += road.k2*i;
            }
            else if (startCross.pos[otherAxis] < targetCross.pos[otherAxis]) {
                shift = +0.3;
                shift -= road.k2*i;
            }
        }
        else {
            if (startCross.pos[otherAxis] > targetCross.pos[otherAxis]) {
                shift = +0.3;
                shift -= road.k1*i;
            }
            else if (startCross.pos[otherAxis] < targetCross.pos[otherAxis]) {
                shift = -0.3;
                shift += road.k1*i;
            }
        }
    }
    
    if (shift)
        shift -= (car.thing.type == 'car') ? 0.05 / 2 : 0.09 / 2;
    
    return shift;
}

    
/**
 * @brief Calculates the coordinate to move the car in the park it wants to park on
 * @param [in] vehicle car the vehicle we need the coordinate
 * @param [in] x|y axis the desired axis
 * @return int the required coordinate
 */
function carToPark(car, axis) {
    var road = city.roads[justRoad(car.container.id)];
    var startCross = city.crossings[road.from];
    var targetCross = city.crossings[road.to];
    if (startCross.pos[axis] !== targetCross.pos[axis]) {
        //TMP
        return road.pPark[axis];
    }
    else {
        //TMP
        return startCross.pos[axis];
    }
}


/**
 * @brief Calculates the coordinate to park the car in a random point of the parking lot
 * @param [in] vehicle car the vehicle we need the coordinate
 * @param [in] x|y axis the desired axis
 * @return int the required coordinate
 */
function carInPark(car, axis) {
    var road = city.roads[justRoad(car.container.id)];
    return road.pPark[axis] + getRandomSign()*(Math.random() / 5);
}


/**
 * @brief Helper to get the size of a vehicle
 * @param [in] car d the vehicle we want the size of
 * @param [in] x|y axis the desired axis
 * @return int the size to associate to the vehicle
 */
function size(d, selected) {
    return d.thing.type === 'bus' ? .09 : .05;
}

    
/*
 *
 *  Let updateCars be the function to update every car present in the state of the client.
 *  This follows the general d3 update pattern. Additional details are documented in the Relazione Finale
 *
 */
var vehiclesGroup = svg.select('#vehicles');

window.updateCars = function() {
    var datiauto = d3.values(city.auto);

    // 1 - Data join
    var auto = vehiclesGroup.selectAll(".auto")
    .data(datiauto, function(d) { return d.thing.id;  });

    
    // 2 - Enter Selection
    var autoEnter = auto.enter()
    .append("rect")
    .attr('class', function(d) { return d.thing.type + ' auto';})
    .attr('width', size)
    .attr('height', size);
        
    
    // 3 - Update Selection
    auto
    .filter(function(d) { return d.needsUpdate !== 0 && d.container.type == 'cross' && d.anim !== 1; })
    .transition()
    .each('start', function(d) { d.anim = 1; return; })
    .duration(100)
    .attr('x', function(d) { return enterInCross(city.crossings[d.container.id].pos.x, this.getAttribute('x')); })
    .attr('y', function(d) { return enterInCross(city.crossings[d.container.id].pos.y, this.getAttribute('y')); })
    .attr('rx', .001)
    .each('end', function(d) { d.anim = 0; d.needsUpdate = 0; d.justCrossed = 0; return; });

    auto
    .filter(function(d) {
        return d.needsUpdate !== 0 && d.container.type == 'lane' && d.anim !== 1 && d.justCrossed == 1; })
    .transition()
    .each('start', function(d) { d.anim = 1; return; })
    .duration(200)
    .attr('x', function(d) { return carToLane(d, 'x'); })
    .attr('y', function(d) { return carToLane(d, 'y'); })
    .transition()
    .duration(function(d) { return d.duration - 500; })
    .attr('x', function(d) { return queueCar(d, 'x'); })
    .attr('y', function(d) { return queueCar(d, 'y'); })
    .each('end', function(d) { d.anim = 0; d.needsUpdate = 0; d.justCrossed = 0; return; });

    auto
    .filter(function(d) { return d.needsUpdate !== 0 && d.container.type == 'lane' &&
        (!d.justCrossed || typeof d.justCrossed === 'undefined'); })
    .transition()
    .duration(function(d) { return d.duration - 500; })
    .attr('x', function(d) { return queueCar(d, 'x'); })
    .attr('y', function(d) { return queueCar(d, 'y'); })
    .each('end', function(d) { d.anim = 0; d.needsUpdate = 0; d.justCrossed = 0; return; });
    
    auto
    .filter(function(d) { return d.needsUpdate !== 0 && d.container.type == 'park' && d.anim !== 1; })
    .transition()
    .each('start', function(d) { d.anim = 1; return; })
    .duration(300)
    .attr('x', function(d) { return carToPark(d, 'x'); })
    .attr('y', function(d) { return carToPark(d, 'y'); })
    .transition()
    .duration(300)
    .attr('x', function(d) { return carInPark(d, 'x'); })
    .attr('y', function(d) { return carInPark(d, 'y'); })
    .each('end', function(d) { d.anim = 0; d.needsUpdate = 0; });

    // selected/unselected state
    auto
    .filter(function(d) {return d.needsSelUpdate && d.selected === true;})
    .classed('selectedThing', true)
    .each(function(d) {d.needsSelUpdate = 0;});

    auto
    .filter(function(d) {return d.needsSelUpdate && d.selected === false;})
    .classed('selectedThing', false)
    .each(function(d) {d.needsSelUpdate = 0;});


    // 4 - Exit selection    
    auto.exit()
    .transition()
    .duration(200)
    .attr('fill-opacity', 0)
    .attr('stroke-opacity', 0)
    .remove();

}

}());
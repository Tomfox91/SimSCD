"use strict";

/**
 * @brief @brief Receives the message that a vehicle is entering a lane;
 *  enqueues the vehicle in the right lane and sets to update the client
 * @param  [in] msg thing entered structure message
 * @param  [in] carId cars's identification 
 * @param  [in] placeId place's identification
 * @return void
 */
function msgCarLane(msg, carId, placeId) {
    var targetCross = city.roads[justRoad(placeId)].to;
    var area = targetCross.substr(targetCross.indexOf('area/')+5);
    area = area.substr(0, area.indexOf('/'));

    if(city.areas[area].subscribed !== 0) {
        city.auto[carId] = msg;
        var auto = city.auto[carId];
        var lanes = city.lanes[justRoad(placeId)].lanes;
        var lane = lookupIntoLaneList(lanes, placeId);

        auto.enqueuedAt = '';
        auto.enqueuedAt = {lane: placeId, pos: lane.queue.length};
        lane.queue.push(auto);
        auto.needsUpdate = 1;
        auto.justCrossed = 1;

    } else {
        vehicleExitedSubscribedAreas(carId);
    }
}
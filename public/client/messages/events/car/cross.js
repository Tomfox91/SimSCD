"use strict";

/**
 * @brief Receives the message that a vehicle is entering a cross;
 *  moves the vehicle from the lane to the cross,
 *  shifts the vehicles in the lane and sets to update the client
 * @param  [in] msg thing entered structure message
 * @param  [in] carId cars's identification 
 * @param  [in] placeId place's identification
 * @return void
 */
function msgCarCross(msg, carId, placeId) {
    var pastQueue = city.auto[carId] ? city.auto[carId].enqueuedAt : undefined;
    city.auto[carId] = msg;
    var auto = city.auto[carId];
    addToContainer(auto, city.crossings, placeId);
    if (pastQueue && pastQueue.lane) {
        var cross = city.crossings[placeId];
        var lanes = city.lanes[justRoad(pastQueue.lane)].lanes;
        var lane = lookupIntoLaneList(lanes, pastQueue.lane);
        lane.queue.shift();
        lane.queue.forEach(function(e, i) {
            e.enqueuedAt.pos--;
            e.needsUpdate = 1;
            e.justCrossed = 0;
        });
    }
    auto.enqueuedAt = null;
    auto.needsUpdate = 1;
}
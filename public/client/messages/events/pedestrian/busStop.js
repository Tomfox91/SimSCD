"use strict";

/**
 * @brief Receives the message that a pedestrian is entering a bus stop;
 *  moves the pedestrian from the road to the bus stop and sets to update the client
 * @param  [in] msg thing entered structure message
 * @param  [in] pedId pedestrian's identifier 
 * @param  [in] placeId place's identifier
 * @return void
 */
function msgPedBusStop(msg, pedId, placeId) {
    var road = city.roads[justRoad(placeId)];

    if (!road.hiddenDestination) {
        var pedone = city.pedestrians[pedId] = msg;
        addToContainer(pedone, city.busStops, placeId);
        pedone.needsUpdate = 1;
    } else {
        pedestrianExitedSubscribedAreas(pedId);
    }
}
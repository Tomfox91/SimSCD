"use strict";

/**
 * @brief Receives the message that a pedestrian is entering a biulding;
 *  moves the pedestrian from the road to the building and sets to update the client
 * @param  [in] msg thing entered structure message
 * @param  [in] pedId pedestrian's identifier
 * @param  [in] placeId place's identifier
 * @return void
 */
function msgPedBuild(msg, pedId, placeId) {
    var road = city.roads[justRoad(placeId)];

    if (!road.hiddenDestination) {
        var pedone = city.pedestrians[pedId] = msg;
        addToContainer(pedone, city.buildings, placeId);
        pedone.needsUpdate = 1;
        checkIncumbent(msg, pedId);
    } else {
        pedestrianExitedSubscribedAreas(pedId);
    }
}
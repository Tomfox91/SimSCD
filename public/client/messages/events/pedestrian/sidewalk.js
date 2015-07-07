"use strict";

/**
 * @brief Receives the message that a pedestrian is entering a sidewalk;
 *  moves the pedestrian from the cross to the building and sets to update the client
 * @param  [in] msg thing entered structure message
 * @param  [in] pedId pedestrian's identifier 
 * @param  [in] placeId place's identifier
 * @return void
 */
function msgPedSidewalk(msg, pedId, placeId) {
    var pedone = city.pedestrians[pedId] = msg;
    if (!city.sidewalks[placeId])
        city.sidewalks[placeId] = {id: placeId, queue: []};
    var sidewalk = city.sidewalks[placeId];
    var road = city.roads[justRoad(placeId)];
    
    var area = placeId.slice(road.to.indexOf('/area/') + 6, road.to.indexOf('/cross'));
    
    if(city.areas[area].subscribed !== 0 && road.hiddenDestination == 0) {
        pedone.enqueuedAt = '';
        pedone.enqueuedAt = {sidewalk: placeId, pos: sidewalk.queue.length};
        sidewalk.queue.push(pedone);

        pedone.needsUpdate = 1;
        pedone.justCrossed = 1;
        checkIncumbent(msg, pedId);
    } else {
        pedestrianExitedSubscribedAreas(pedId);
    }
}
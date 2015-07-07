"use strict";

/**
 * @brief Receives the message that a pedestrian is entering a cross;
 *  moves the pedestrian from the sidewalk to the cross and sets to update the client
 * @param  [in] msg thing entered structure message
 * @param  [in] pedId pedestrian's identifier 
 * @param  [in] placeId place's identifier
 * @return void
 */
function msgPedCross(msg, pedId, placeId) {
    if (city.pedestrians[pedId]) {
        var pastSidewalk = city.pedestrians[pedId].container.type == 'sidewalk' ?
            city.sidewalks[city.pedestrians[pedId].container.id] : null;
        if (pastSidewalk !== null) {
            var cross = city.crossings[placeId];

            pastSidewalk.queue.shift();
            pastSidewalk.queue.forEach(function(e, i) {
                e.enqueuedAt.pos--;
                e.needsUpdate = 1;
                e.justCrossed = 0;
            });
        }
    }
    var pedone = city.pedestrians[pedId] = msg;
    addToContainer(pedone, city.crossings, placeId);
    pedone.needsUpdate = 1;
    checkIncumbent(msg, pedId);
}
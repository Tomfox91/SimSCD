"use strict";

/**
 * @brief Changes the color of the traffic lights, alternating red with green and update the crosses
 * @param  [in] msg message containing the roads who have green light
 * @return void
 */
function msgLights(msg) {
    var cross = city.crossings[msg.cross];
    cross.N = 'red';
    cross.S = 'red';
    cross.W = 'red';
    cross.E = 'red';
    msg.roads.forEach(function(road) {
        cross[road] = 'green';
    });

    updateCrosses();
}
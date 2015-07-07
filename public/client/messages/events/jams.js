"use strict";

/**
 * @brief Receives the jam event and creates the overlay to highlight the road
 * @param  [in] msg message containing the road with a jam
 * @return void
 */
function msgRoadJammed(msg) {
    createRoadOverlay(svg.select('#temproadoverlay'), city.roads[msg.road])
        .attr('class', 'roadoverlayjammed')
        .attr('data-roadName', msg.road);
}

/**
 * @brief Receives the no longer jammed message and removes the overlay
 * @param  [in] msg message containing the road no longer jammed
 * @return void
 */
function msgRoadNoLongerJammed(msg) {
    svg.select('#temproadoverlay .roadoverlayjammed[data-roadName="' + msg.road + '"]').remove();
}
"use strict";

/**
 * @brief Receives the Area message from the server, containing the list of areas
 * of the simulation, and displays them in the subscribe dialog and in the map
 * @param [in] area_message msg The Areas message from the server, containing the list of available areas.
 * @return void
 */
function msgAreas(msg) {
    var areaList = '';
    var areas = svg.select('#areas')
    for (var i in msg.contained) {
        i = msg.contained[i];
        var areaNumber = justArea(i.name);
        city.areas[areaNumber] = i;
        city.areas[areaNumber].subscribed = 0;
        areaList += '<li><input type="checkbox" class="subscribe" data-target="' +
            i.name + '"><label>' + i.name + ' ( ' + i.pos.x + ', ' + i.pos.y + ' ) </label></li>';
        areas.append('circle')
        .attr("class", "areaPlaceholder")
        .attr("data-target", i.name)
        .attr("cx", i.pos.x)
        .attr("cy", i.pos.y)
        .attr("r", 3);
    }
    $('#areascontainer').html('<ul>' + areaList + '</ul>');
    
    splashManager.conditionMet();
}
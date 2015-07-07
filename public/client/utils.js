"use strict";

/**
 * @brief Util to get the coordinates of the two crossings a road belongs to.
 * The return value is meaningful only if both values are not undefined, otherwise
 * one of the two endpoints is not present on the client's state.
 * @param [in] Road d the road we want to get the couple of coordinates of.
 * @return Object with the coordinates of the two crossings. If any of the two
 *         values is undefined, it means that the client is not subscribed to the container that cross belongs to.
 */
function roadCoordinates(d) {
    var p1 = (typeof d === 'undefined' || typeof city.crossings[d.from] === 'undefined') ?
        null : city.crossings[d.from].pos;
    var p2 = (typeof d === 'undefined' || typeof city.crossings[d.to] === 'undefined') ?
        null : city.crossings[d.to].pos;
    return {p1: p1, p2: p2};
}

/*
 *  String operations to manipulate IDs of entities if a fast decision on naming
 * (without expensive lookups) is to be done
 *  Assume the ID is left unchanged, any client modification on the entities ID
 * causes any caller of such functions to (eventually) fail.
 */
function keepLane(string) {
    return string.match(/\d+$/)[0];
}

function justRoad(string) {
    return string.match(/akka.*\/road\/[\dAB]+/)[0];
}

function justArea(string) {
    return string.match("/city/area/([0-9]+)")[1];
}

function lookupIntoLaneList(list, name) {
    var result = null;
    list.forEach(function(e) {
        if (e.name == name)
            result = e
            });
    return result;
}

function getSide(pedone) {
    return pedone.container.id.substr(-1);
}

/*
 *  Helpers to manipulate random numbers and signs, for scattering purposes.
 */
function getRandomInt(min, max) {
    return Math.floor(Math.random() * (max - min)) + min;
}
function getRandomSign() {
    return Math.random() > 0.5 ? 1 : -1;
}


/*
 *  Helper to deactivate busy parts of the user interface, eg.,
 * when waiting for some asynchronous message to arrive.
 */
window.ReentrantLoadingOverlay = function() {
    var num = 0;

    return {
        show: function() {
            if (num === 0) {
                $('#left_panel').css({opacity: .5});
            }
            num++;
        },
        hide: function() {
            num--;
            if (num === 0) {
                $('#left_panel').animate({opacity: 1}, 400);
            }
        }
    }
}();




/*
 * Debug functions, to log events and development communication.
 */

function logger (log, arg) {
    if (opMode == 'DEVELOPMENT')
        console.log(log, arg);
}
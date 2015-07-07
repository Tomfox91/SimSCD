"use strict";

/**
 * @brief Remove the vehicle identified by id from the DOM and set to update all the vehicle
 * @param  [in] id vehicle's identifier
 * @return void
 */
function vehicleExitedSubscribedAreas(id) {
    if (is_selected(id)) {
        cp_deselect();
    }
    
    delete city.auto[id];
    UpdateManager.veh = true;
}

/**
 * @brief Removes the pedestrian identified by id from the DOM an set to update all the pedestrian
 * @param  [in] id pedestrian's identifier
 * @return void
 */
function pedestrianExitedSubscribedAreas(id) {
    if (is_selected(id)) {
        cp_deselect();
    }

    delete city.pedestrians[id];
    UpdateManager.ped = true;
}

/**
 * @brief Receives the message from the server, in case the thing exiting
 * is a pedestrian and the area is unsubscribed removes the pedestrian
 * @param  [in] msg message containing the event occurred
 * @return void
 */
function thingExitedArea(msg) {
    if (! city.areas[msg.to].subscribed && msg.thing.type === 'pedestrian') {
        pedestrianExitedSubscribedAreas(msg.thing.id)
    }
}

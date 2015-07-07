"use strict";

/**
 * @brief Receive the confirmation of the area's subscription and shows the area
 * @param  [in] msg the area subscripted
 * @return void
 */
function msgSubscribe(msg) {
    city.areas[justArea(msg.subscribedTo)].subscribed = 1;
    ReentrantLoadingOverlay.hide();
    logger("Subscription to " + msg.subscribedTo, " successful");
}
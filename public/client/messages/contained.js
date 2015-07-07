"use strict";

/**
 * @brief Manages subscription request adding or removing areas
 */
window.SubscriptionManager = function() {
    var stillMissing = {};

    return {
        addMissing: function(area, num) {
            var oldMissing = stillMissing[area];
            if (!oldMissing)
                oldMissing = 0;
            stillMissing[area] = oldMissing + num;
        },
        removeMissing: function(area) {
            stillMissing[area]--;

            if (stillMissing[area] === 0) {
                mys.send(JSON.stringify({
                    dest: city.areas[area].name + '/eventBus',
                    request: "subscribe", 
                    callback: "events"
                }));
            }
        }
    }
}();

/**
 * @brief Receives the message that a pedestrian is moving to a building and parking
 * a car or going to a bus stop 
 * @param  [in] type type of container
 * @param  [in] msg  received message
 * @return void
 */
function msgContained(type, msg) {
    SubscriptionManager.removeMissing(justArea(msg.container));

    msg.container = msg.container.replace('/busStop', '')

    msg.contained.forEach(function(t) {
        var obj = {thing: t, container: {type: type, id: msg.container}};

        switch (type) {
            case 'build':
                msgPedBuild(obj, t.id, msg.container);
                UpdateManager.ped = true;
                break;

            case 'busStop':
                msgPedBusStop(obj, t.id, msg.container);
                UpdateManager.ped = true;
                break;

            case 'park':
                msgCarPark(obj, t.id, msg.container);
                UpdateManager.veh = true;
                break;
        }
    });
}
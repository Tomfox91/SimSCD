"use strict";

/**
 * @brief Binds a function to the onmessage server WebSocket event. This function types
 * the channel according to the defined protocol, where every message carries information
 * on its type and the payload, and delegates to appropriate functions the message handling.
 * @param [in] event_message event The event message to be delegated, containing type information and payload.
 * @return void
 */
mys.onmessage = function (event) {
    var msg = JSON.parse(event.data);

    switch (msg.callback) {
    
    case "areas":
        msgAreas(msg);
        break;
            
    case "road":
        msgRoad(msg);
        break;
            
    case "cross":
        msgCross(msg);
        break;
            
    case "lane":
        msgLane(msg);
        break;
            
    case "events":
        UpdateManager.cp = true;

        switch (msg.event) {
        case "changeLights":
            msgLights(msg);
            break;
                
        case "subscribeConfirmation":
            msgSubscribe(msg);
            break;
                
        case "thingEnteredStructure":
            delete msg.callback;
            delete msg.event;

            if (is_selected(msg.thing.id)) {
                msg.selected = true;
            }

            switch (msg.thing.type) {
                case "pedestrian":
                    var pedId = msg.thing.id;
                    var placeId = msg.container.id;
                    removeFromContainer(pedId, city.pedestrians);
                    
                    switch (msg.container.type) {
                        case "sidewalk":
                            msgPedSidewalk(msg, pedId, placeId);
                            break;

                        case "cross":
                            msgPedCross(msg, pedId, placeId);
                            break;
                            
                        case "busStop":
                            msgPedBusStop(msg, pedId, placeId);
                            break;
                            
                        case "build":
                            msgPedBuild(msg, pedId, placeId);
                            break;

                        case "park":
                            msgPedPark(msg, pedId, placeId);
                            break;
                    }
                    UpdateManager.ped = true;
                    break;

                case "car":
                case "bus":
                    var carId = msg.thing.id;
                    var placeId = msg.container.id;
                    removeFromContainer(carId, city.auto);
                    
                    switch (msg.container.type) {
                        case "lane":
                            msgCarLane(msg, carId, placeId);
                            break;
                            
                        case "cross":
                            msgCarCross(msg, carId, placeId);
                            break;
                            
                        case "park":
                            msgCarPark(msg, carId, placeId);
                            break;
                    }
                    UpdateManager.veh = true;
                    break;
            }
            break;
                
        case "pedestrianTakingBus":
            var pedId = msg.thing.id;
            msgPedBus(msg, pedId);
            break;

        case "thingExitedArea":
            thingExitedArea(msg)
            break;

        case "roadJammed":
            msgRoadJammed(msg);
            break;

        case "roadNoLongerJammed":
            msgRoadNoLongerJammed(msg);
            break;

        default:
            logger('Unknown message ' + msg.event + ' received: ', msg);
        }
            
        break;

    case "time":
        clock.init(msg);
        UpdateManager.start();
        break;

    case "unsubscribed":
        console.log('unsubscribed', msg)
        deleteUnsubscribed(justArea(msg.unsubscribedFrom))
        break

    case "system":
        switch (msg.event) {
        case "systemShuttingDown":
            serverShutDown(msg);
            break;    
        }
        break;
            
    case "busStop": case "build": case "park":
        msgContained(msg.callback, msg);
        break;

    default: console.log(msg); break;
    }
};


/*
 *
 *  Displays an error message if the WebSocket dies unexpectedly.
 *
 *
 */
mys.onclose = function() {
    $('#splashtext').text('Error connecting to the server');
    $('#splash').show();
    $('#splashbutton').show();
}
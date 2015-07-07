"use strict";

!(function() {
    var removeSel = $('#removeSelection');
    var gotoSel = $('#gotoSelection');
    var manualSel = $('#manualThingSelect');

    /**
     * @brief Highlight a dom element
     * @param [in] that element to highlight
     */
    function addHighlight(that) {
        d3.select(that).classed('overlayselected', true);
    }

    /**
     * @brief Deselects the selected entity and updates the selection
     * @return void
     */
    function deselect() {
        if (selected_entity) {
            switch (selected_entity.type) {
                case 'pedestrians':
                    city.pedestrians[selected_entity.data.thing.id].selected = false;
                    city.pedestrians[selected_entity.data.thing.id].needsSelUpdate = 1;
                    UpdateManager.pedImmediate();
                break;
    
                case 'cars': case 'buss':
                    city.auto[selected_entity.data.thing.id].selected = false;
                    city.auto[selected_entity.data.thing.id].needsSelUpdate = 1;
                    UpdateManager.vehImmediate();
                break;
    
                default:
                    svg.selectAll('.overlayselected').classed('overlayselected', false);
                break;
            } 
        }
    }

    /**
     * @brief Manages a crossing selection and updates the view
     * @return void
     */
    $('body').on('click', 'rect.incrocio', function() {
        deselect();
        removeSel.show();
        gotoSel.hide();
        manualSel.hide();

        selected_entity = {
            type: "crossings",
            data: this.__data__
        };
        addHighlight(this);
        UpdateManager.cpImmediate();
    });

    /**
     * @brief Manages an entity selection and updates the view
     * @return void
     */
    $('body').on('click', '.selectable', function() {
        deselect();
        removeSel.show();
        gotoSel.hide();
        manualSel.hide();

        selected_entity = {
            type: this.getAttribute("data-contType"),
            data: city[this.getAttribute("data-contType")][this.getAttribute("data-contName")]
        };
        addHighlight(this);
        UpdateManager.cpImmediate();
    });

    /**
     * @brief Manages the selection of a pedestrian and updates the view
     * @param  [in] id identifier of the pedestrian
     * @return void
     */
    window.cp_selectPedestrian = function(id) {
        deselect();
        removeSel.show();
        gotoSel.show();
        manualSel.hide();

        city.pedestrians[id].selected = true;
        city.pedestrians[id].needsSelUpdate = 1;
        selected_entity = {
            type: 'pedestrians',
            data: {
                thing: {
                    id: id,
                    info: city.pedestrians[id].thing.info,
                    carId: city.pedestrians[id].thing.carId
                }
            } 
        }
        UpdateManager.pedImmediate();
        UpdateManager.cp = true;
    }

    /**
     * @brief Manages the selection of a vehicle and update the view
     * @param  [in] id   identifier of the vehicle
     * @param  [in] type vehicle's typology
     * @return void
     */
    window.cp_selectVehicle = function(id, type) {
        deselect();
        removeSel.show();
        gotoSel.show();
        manualSel.hide();

        city.auto[id].selected = true;
        city.auto[id].needsSelUpdate = 1;
        selected_entity = {
            type: type + 's',
            data: {
                thing: {
                    id: id,
                    owner: city.auto[id].thing.owner
                }
            }
        }
        UpdateManager.vehImmediate();
        UpdateManager.cp = true;
    }

    /**
     * Manages a selection of an entity of the city
     * @return void
     */
    $('#c_entities, #c_selected').on('click', 'a.selectThing', function() {
        var type = this.getAttribute('data-thingType');
        var id = this.getAttribute('data-thingId');

        if (selected_entity.type === 'buss' || selected_entity.type === 'cars') {
            incumbent_pedestrian_id = id;
        } else if (type === 'pedestrian') {
            cp_selectPedestrian(id);
        } else {
            cp_selectVehicle(id, type);
        }
        UpdateManager.cpImmediate();
    });

    /**
     * Manages the deselection of an entity
     * @return void
     */
    window.cp_deselect = function() {
        deselect();
        removeSel.hide();
        gotoSel.hide();
        manualSel.show();

        selected_entity = null;
        incumbent_pedestrian_id = null;
        UpdateManager.cpImmediate();
    }

    removeSel.click(cp_deselect);

    /**
     * @brief Shows the selected entity on the center of the page
     * @return void
     */
    gotoSel.click(function() {
        var node = svg.select('.selectedThing')[0][0];
        if (node.tagName === 'circle') {
            var x = node.getAttribute('cx');
            var y = node.getAttribute('cy');
        } else {
            var x = node.getAttribute('x');
            var y = node.getAttribute('y');
        }

        if (svgpanzoom.getZoom() < 40) {
            svgpanzoom.zoom(40);
        }
        svgpanzoom.goto({x: x, y: y});
    });

    /**
     * @brief Provides the manual selection of the entities to the user
     * @return false
     */
    $('#mtsSelect').click(function() {
        function err() {
            $('#mtsIdSel').parent().addClass('error');
            $('#mtsError').remove();
            $('#mtsIdSel').after('<small class="error" id="mtsError">Not found</small>');
        }

        var id = $('#mtsIdSel').val();
        var type = $('#manualThingSelectModal input[name=mtsTSel]:checked').val();
        if (type === 'pedestrian') {
            if (city.pedestrians[id]) {
                cp_selectPedestrian(id);
            } else {
                err();
                return false;
            }
        } else {
            if (city.auto[id] && city.auto[id].thing.type === type) {
                cp_selectVehicle(id, type);
            } else {
                err();
                return false;
            }
        }
        $('#mtsIdSel').parent().removeClass('error');
        $('#mtsError').remove();
        $('#mtsSelect').foundation('reveal', 'close');
        return false;
    });


    /**
     * @brief Sends a request to the server to subscribe to an area
     * @return void
     */
    function subscribe() {
        ReentrantLoadingOverlay.show();

        var target = $(this).data('target');
        logger('subscribing to', target);

        $("#subModal input.subscribe[data-target='" + target + "']").prop('checked', true);
        $("#areas .areaPlaceholder[data-target='" + target + "']").remove();

        mys.send(JSON.stringify({
            "dest": target + '/cross',
            "request": "getContained",
            "callback": "cross"
        }));
    }

    /**
     * @brief Sends a request to the server to unsubscribe from an area
     * @param  [in] area_id identifier of the area
     * @return void
     */
    function unsubscribe(area_id) {

        logger('unsubscribing from', $(this).data('target'))
        var target= $(this).data('target');

        mys.send(JSON.stringify({
            dest: target + '/eventBus',
            request: "unsubscribe", 
            callback: "unsubscribed"
        }));

    }
    
    $('#subModal').on('click', 'input:not(:checked)', unsubscribe);
    $('#subModal').on('click', 'input:checked', subscribe);
    $('#areas').on('click', '.areaPlaceholder', subscribe);

    /**
     * @brief Generates a jam in a road sending a request to the server
     * @return void
     */
    $('button#jamButton').click(function() {
        if (selected_entity && selected_entity.data.name &&
            selected_entity.data.name.indexOf("road") !== -1) {
            svg.select('#temproadoverlay .roadoverlayjammed[data-roadName="' +
                selected_entity.data.name + '"]').remove();
            mys.send(JSON.stringify({
                "dest": selected_entity.data.name,
                "request": "setJam",
                "vehSlowFact": parseInt($('#jamVehFactor').html()),
                "pedSlowFact": parseInt($('#jamPedFactor').html()),
                "duration": parseInt($('#jamtime').html()) * 1000
            }));
        }
    });
    
    /**
     * @brief Sends a request to the server to terminate the simulation
     * @return void
     */
    $('button#shutdownButton').click(function() {
        mys.send(JSON.stringify({
            "dest": "akka://infra/user/city/",
            "request": "shutdown"
        }));
    });

    /**
     * @brief Reloads the page in case communication errors
     * @return void
     */
    $('body').on('click', 'button#btnReconnect', function() {
        document.location.reload();
    });
}());
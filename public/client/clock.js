"use strict";
/**
 * @brief Clock manager. Provides utils to initialize a Date object to the current
 * simulation date and time, and to write its value in the Control Panel.
 * @return Object with as init parameter the closure of a function to set the date
 *         to the content of a given time message.
 */
var clock = function() {
    var dayDuration;
    var epoch;

    function setTime() {
        var now = Math.floor(Date.now() - epoch);
        var date = new Date(now / dayDuration * 86400000).toLocaleString([], {timeZone: 'UTC'});
        $('#current_time').html(date + ' <small>' + (now % dayDuration / dayDuration * 24).toFixed(3) + '</small>');
    }

    return {
        init: function(mess) {
            dayDuration = mess.dayDuration;
            epoch = Date.now() - mess.now;
            setTime();
            setInterval(setTime, 1000);
        }
    };
}();
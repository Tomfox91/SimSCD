"use strict";

/**
 * @brief Receives the shutdown message and closes the client
 * @param  [in] msg the error message
 * @return void
 */
function serverShutDown(msg) {
    mys.onclose = null;

    console.debug(msg);

    $('#shutModal').foundation('reveal', 'close');
    if (msg.correctly) {
	    $('#splashtext').html('<p>Server was shut down correctly.</p>');
    } else {
	    $('#splashtext').html('<p>Server was shut down due to an error.</p>' +
	    	'<p>Resason: ' + msg.error + '</p>');
    }
    $('#splash').show();
    $('#splashbutton').hide();
}

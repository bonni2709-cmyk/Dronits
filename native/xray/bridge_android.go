//go:build android

package main

/*
#include <stdint.h>
int wot_protect_socket(int fd);
*/
import "C"

import (
	"errors"
	"syscall"
	"unsafe"

	"github.com/bonni2709-cmyk/Dronits/native/xray/engine"
	"github.com/xtls/xray-core/transport/internet"
)

var mobileEngine engine.Engine
var controllerError error

func protectSocket(network, address string, raw syscall.RawConn) error {
	allowed := false
	if err := raw.Control(func(fd uintptr) {
		allowed = C.wot_protect_socket(C.int(fd)) != 0
	}); err != nil {
		return err
	}
	if !allowed {
		return errors.New("VPN socket protection denied")
	}
	return nil
}

func init() {
	controllerError = internet.RegisterDialerController(protectSocket)
	if controllerError == nil {
		controllerError = internet.RegisterListenerController(protectSocket)
	}
}

//export WotXrayStart
func WotXrayStart(data *C.char, length C.int) C.int {
	if controllerError != nil {
		return C.int(engine.InternalError)
	}
	if data == nil || length <= 0 || length > 262144 {
		return C.int(engine.InvalidConfig)
	}
	config := C.GoBytes(unsafe.Pointer(data), length)
	defer clear(config)
	return C.int(mobileEngine.Start(config))
}

//export WotXrayStop
func WotXrayStop() { mobileEngine.Stop() }

//export WotXrayRunning
func WotXrayRunning() C.int {
	if mobileEngine.IsRunning() {
		return 1
	}
	return 0
}

package engine

import (
	"bytes"
	"sync"

	"github.com/xtls/xray-core/core"
)

const (
	OK = iota
	InvalidConfig
	AlreadyRunning
	StartFailed
	InternalError
)

// Engine owns one in-process Xray instance. Error codes never contain credentials.
type Engine struct {
	mu sync.Mutex
	instance *core.Instance
}

func (e *Engine) Start(config []byte) (code int) {
	e.mu.Lock()
	defer e.mu.Unlock()
	defer func() {
		if recover() != nil {
			if e.instance != nil { _ = e.instance.Close(); e.instance = nil }
			code = InternalError
		}
	}()
	if e.instance != nil { return AlreadyRunning }
	parsed, err := core.LoadConfig("json", bytes.NewReader(config))
	if err != nil { return InvalidConfig }
	instance, err := core.New(parsed)
	if err != nil { return InvalidConfig }
	e.instance = instance
	if err := instance.Start(); err != nil {
		_ = instance.Close()
		e.instance = nil
		return StartFailed
	}
	return OK
}

func (e *Engine) Stop() {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.instance != nil {
		_ = e.instance.Close()
		e.instance = nil
	}
}

func (e *Engine) IsRunning() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.instance != nil && e.instance.IsRunning()
}

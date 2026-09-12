package com.sg.linuxgo

class TerminalSessionManager(
    var onSessionsChanged: (() -> Unit)? = null,
    var onActiveSessionChanged: ((Int) -> Unit)? = null
) {
    private val _sessions = ArrayList<TerminalSession>()
    val sessions: List<TerminalSession> get() = _sessions

    var activeSessionIndex: Int = -1
        private set

    fun getActiveSession(): TerminalSession? {
        return _sessions.getOrNull(activeSessionIndex)
    }

    fun addSession(session: TerminalSession) {
        _sessions.add(session)
        activeSessionIndex = _sessions.size - 1
        onSessionsChanged?.invoke()
        onActiveSessionChanged?.invoke(activeSessionIndex)
    }

    fun removeSessionAt(index: Int): TerminalSession? {
        if (index < 0 || index >= _sessions.size) return null
        val session = _sessions[index]
        _sessions.removeAt(index)
        
        if (_sessions.isEmpty()) {
            activeSessionIndex = -1
        } else {
            activeSessionIndex = Math.min(activeSessionIndex, _sessions.size - 1)
        }
        
        onSessionsChanged?.invoke()
        onActiveSessionChanged?.invoke(activeSessionIndex)
        return session
    }

    fun selectSession(index: Int) {
        if (index >= -1 && index < _sessions.size) {
            activeSessionIndex = index
            onActiveSessionChanged?.invoke(activeSessionIndex)
        }
    }

    fun renameSession(index: Int, newName: String) {
        if (index >= 0 && index < _sessions.size) {
            _sessions[index].title = newName
            onSessionsChanged?.invoke()
        }
    }

    private val trimListener = MemoryTrimRegistry.TrimListener { level ->
        trimMemory(level)
    }

    init {
        MemoryTrimRegistry.register(trimListener)
    }

    fun trimMemory(level: Int) {
        for (session in _sessions) {
            session.emulator.trimMemory(level)
        }
    }

    fun clear() {
        _sessions.clear()
        activeSessionIndex = -1
        onSessionsChanged?.invoke()
        onActiveSessionChanged?.invoke(activeSessionIndex)
    }

    fun destroy() {
        clear()
        MemoryTrimRegistry.unregister(trimListener)
    }
}

package com.sg.linuxgo.bootstrap

fun buildPocketLinuxResizeScript(): String = """
    import ctypes
    import os
    import sys
    import time
    import traceback

    print("Python resize daemon started...", flush=True)
    print("Arguments:", sys.argv, flush=True)

    try:
        try:
            X11 = ctypes.CDLL("libX11.so.6")
            print("Loaded libX11.so.6", flush=True)
        except Exception as e:
            print("Failed to load libX11.so.6, trying libX11.so...", e, flush=True)
            try:
                X11 = ctypes.CDLL("libX11.so")
                print("Loaded libX11.so", flush=True)
            except Exception as e2:
                print("Failed to load libX11.so:", e2, flush=True)
                sys.exit(1)

        XOpenDisplay = X11.XOpenDisplay
        XOpenDisplay.restype = ctypes.c_void_p
        XOpenDisplay.argtypes = [ctypes.c_char_p]

        XDefaultRootWindow = X11.XDefaultRootWindow
        XDefaultRootWindow.restype = ctypes.c_ulong
        XDefaultRootWindow.argtypes = [ctypes.c_void_p]

        XQueryTree = X11.XQueryTree
        XQueryTree.restype = ctypes.c_int
        XQueryTree.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_void_p), ctypes.POINTER(ctypes.c_uint)]

        XMoveResizeWindow = X11.XMoveResizeWindow
        XMoveResizeWindow.restype = ctypes.c_int
        XMoveResizeWindow.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int, ctypes.c_int, ctypes.c_uint, ctypes.c_uint]

        XMapRaised = X11.XMapRaised
        XMapRaised.restype = ctypes.c_int
        XMapRaised.argtypes = [ctypes.c_void_p, ctypes.c_ulong]

        XFree = X11.XFree
        XFree.restype = ctypes.c_int
        XFree.argtypes = [ctypes.c_void_p]

        XCloseDisplay = X11.XCloseDisplay
        XCloseDisplay.restype = ctypes.c_int
        XCloseDisplay.argtypes = [ctypes.c_void_p]

        XGetGeometry = X11.XGetGeometry
        XGetGeometry.restype = ctypes.c_int
        XGetGeometry.argtypes = [ctypes.c_void_p, ctypes.c_ulong, ctypes.POINTER(ctypes.c_ulong), ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_int), ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint), ctypes.POINTER(ctypes.c_uint)]

        display_name = os.environ.get("DISPLAY", ":0").encode('utf-8')
        print(f"Connecting to display {display_name.decode()}...", flush=True)
        display = XOpenDisplay(display_name)
        if not display:
            print("Cannot open display", flush=True)
            sys.exit(1)

        root = XDefaultRootWindow(display)
        print(f"Root window ID: {root}", flush=True)

        # Monitor and resize in background daemon loop
        while True:
            root_ret = ctypes.c_ulong()
            x_ret = ctypes.c_int()
            y_ret = ctypes.c_int()
            w_ret = ctypes.c_uint()
            h_ret = ctypes.c_uint()
            border_ret = ctypes.c_uint()
            depth_ret = ctypes.c_uint()
            
            if XGetGeometry(display, root, ctypes.byref(root_ret), ctypes.byref(x_ret), ctypes.byref(y_ret), ctypes.byref(w_ret), ctypes.byref(h_ret), ctypes.byref(border_ret), ctypes.byref(depth_ret)) != 0:
                w_res = w_ret.value
                h_res = h_ret.value
                
                root_ret_tree = ctypes.c_ulong()
                parent_ret_tree = ctypes.c_ulong()
                children_ret = ctypes.c_void_p()
                nchildren_ret = ctypes.c_uint()
                
                if XQueryTree(display, root, ctypes.byref(root_ret_tree), ctypes.byref(parent_ret_tree), ctypes.byref(children_ret), ctypes.byref(nchildren_ret)) != 0:
                    if nchildren_ret.value > 0:
                        child_array = ctypes.cast(children_ret, ctypes.POINTER(ctypes.c_ulong))
                        for i in range(nchildren_ret.value):
                            child = child_array[i]
                            
                            c_root = ctypes.c_ulong()
                            c_x = ctypes.c_int()
                            c_y = ctypes.c_int()
                            c_w = ctypes.c_uint()
                            c_h = ctypes.c_uint()
                            c_border = ctypes.c_uint()
                            c_depth = ctypes.c_uint()
                            
                            if XGetGeometry(display, child, ctypes.byref(c_root), ctypes.byref(c_x), ctypes.byref(c_y), ctypes.byref(c_w), ctypes.byref(c_h), ctypes.byref(c_border), ctypes.byref(c_depth)) != 0:
                                if c_w.value != w_res or c_h.value != h_res or c_x.value != 0 or c_y.value != 0:
                                    print(f"Resizing window {child} from {c_w.value}x{c_h.value} at ({c_x.value},{c_y.value}) to {w_res}x{h_res} at (0,0)...", flush=True)
                                    XMoveResizeWindow(display, child, 0, 0, w_res, h_res)
                                    XMapRaised(display, child)
                                    X11.XFlush(display)
                    XFree(children_ret)
            time.sleep(0.5)
            
        XCloseDisplay(display)
    except Exception as ex:
        print("Unhandled exception in python script:", flush=True)
        traceback.print_exc(file=sys.stdout)
        sys.exit(1)
""".trimIndent()

package com.ptport.android;

import org.libsdl.app.SDLActivity;

/** The game: SDL's activity, which loads libSDL3.so and then libmain.so and calls SDL_main in it. */
public class PTActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL3", "main" };
    }
}

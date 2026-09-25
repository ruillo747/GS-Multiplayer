package com.gsmultiplayer.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Grants the button list of vanilla screens so mixins can add entries. */
@Mixin(Screen.class)
public interface ScreenAccessor {

    @Accessor("buttons")
    List<ClickableWidget> getButtons();
}

package com.gsmultiplayer.mixin;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes Screen#addButton so our mixins can register buttons the same way
 * vanilla code does: it adds the widget to BOTH the render list and the
 * children list. Adding to the render list only would produce a button that
 * is visible but never receives clicks.
 */
@Mixin(Screen.class)
public interface ScreenAddButtonInvoker {

    @Invoker("addButton")
    <T extends ClickableWidget> T gsmultiplayer$addButton(T button);
}

package com.conduitrouter.gui;

import com.conduitrouter.conduitrouter;
import com.conduitrouter.network.DirectionResponsePacket;
import com.conduitrouter.network.OpenDirectionGuiPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;


public class DirectionScreen extends Screen {

    private final OpenDirectionGuiPacket.Phase phase;

    private static final Direction[] DIRS = {
            Direction.UP, Direction.DOWN,
            Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST
    };

    public DirectionScreen(OpenDirectionGuiPacket.Phase phase) {
        super(Component.literal("Choose Direction"));
        this.phase = phase;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;
        int btnW = 90;
        int btnH = 20;
        int gap = 4;

        for (int i = 0; i < DIRS.length; i++) {
            Direction dir = DIRS[i];
            int col = i % 2;
            int row = i / 2;
            int x = centerX - btnW - gap / 2 + col * (btnW + gap);
            int y = centerY - 30 + row * (btnH + gap);

            this.addRenderableWidget(Button.builder(
                            Component.literal(dir.getName().toUpperCase()),
                            btn -> send(dir.get3DDataValue()))
                    .bounds(x, y, btnW, btnH).build());
        }

        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), btn -> this.onClose())
                .bounds(centerX - btnW / 2, centerY + 55, btnW, btnH).build());
    }

    private void send(int dirIndex) {
        conduitrouter.NETWORK.sendToServer(new DirectionResponsePacket(phase, dirIndex));
        this.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        String title = phase == OpenDirectionGuiPacket.Phase.INITIAL
                ? "Choose Start Direction"
                : "Choose Next Segment Direction";
        graphics.drawCenteredString(this.font, title, this.width / 2, this.height / 2 - 55, 0xFFFFFF);
        graphics.drawCenteredString(this.font, "§7The pipe will move along this axis",
                this.width / 2, this.height / 2 - 43, 0xAAAAAA);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
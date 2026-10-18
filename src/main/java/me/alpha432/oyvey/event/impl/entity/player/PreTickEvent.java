package me.alpha432.oyvey.event.impl.entity.player;

import me.alpha432.oyvey.event.Event;

/**
 * Posted at the very start of {@code LocalPlayer.tick()}, i.e. before this tick's movement packet is sent.
 *
 * <p>This is the phase in which a vanilla client sends its slot-change and use-item packets. Anything that
 * interacts with the server on the player's behalf should do it here, not in {@link TickEvent}, which is posted at
 * the end of {@code LocalPlayer.tick()} after the movement packet has already gone out.
 */
public class PreTickEvent extends Event {
}

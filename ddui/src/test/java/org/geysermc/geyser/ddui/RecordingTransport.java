/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Geyser
 */

package org.geysermc.geyser.ddui;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for a connection, so a screen can be asserted on by what it puts on the wire.
 */
public final class RecordingTransport implements DduiTransport {

    private final List<BedrockPacket> sent = new ArrayList<>();

    @Override
    public void sendDduiPacket(BedrockPacket packet) {
        sent.add(packet);
    }

    public List<BedrockPacket> sent() {
        return sent;
    }

    @SuppressWarnings("unchecked")
    public <T extends BedrockPacket> List<T> of(Class<T> type) {
        List<T> matches = new ArrayList<>();
        for (BedrockPacket packet : sent) {
            if (type.isInstance(packet)) {
                matches.add((T) packet);
            }
        }
        return matches;
    }

    public void clear() {
        sent.clear();
    }
}

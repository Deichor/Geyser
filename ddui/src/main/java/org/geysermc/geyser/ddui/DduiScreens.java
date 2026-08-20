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

/**
 * The screens the vanilla client already ships, and the datastore they read.
 *
 * <p>A screen supplied by a resource pack works exactly the same way - it just carries its own id
 * and property prefix, which is why {@link ScreenSession} takes both rather than assuming these.
 */
public final class DduiScreens {

    /**
     * Every vanilla screen reads its data out of the datastore named after the namespace itself.
     */
    public static final String VANILLA_DATA_STORE = "minecraft";

    public static final String CUSTOM_FORM = "minecraft:custom_form";
    public static final String CUSTOM_FORM_PROPERTY_PREFIX = "custom_form_data_";

    public static final String MESSAGE_BOX = "minecraft:message_box";
    public static final String MESSAGE_BOX_PROPERTY_PREFIX = "message_box_data_";

    private DduiScreens() {
    }
}

/*
 * Copyright (c) 2008-2022, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.server.tcp;

import com.hazelcast.internal.networking.HandlerStatus;
import com.hazelcast.internal.networking.OutboundHandler;
import com.hazelcast.internal.nio.ConnectionType;
import com.hazelcast.internal.server.ServerConnection;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.nio.ByteBuffer;

import static com.hazelcast.internal.networking.HandlerStatus.CLEAN;
import static com.hazelcast.internal.networking.HandlerStatus.DIRTY;
import static com.hazelcast.internal.nio.IOUtil.compactOrClear;
import static com.hazelcast.internal.nio.Protocols.CLUSTER;
import static com.hazelcast.internal.nio.Protocols.PROTOCOL_LENGTH;
import static com.hazelcast.internal.util.JVMUtil.upcast;
import static com.hazelcast.internal.util.StringUtil.stringToBytes;

public class MemberProtocolEncoder extends OutboundHandler<Void, ByteBuffer> {

    private final OutboundHandler[] outboundHandlers;
    private volatile boolean encoderCanReplace;

    private boolean clusterProtocolBuffered;

    /**
     * Decodes first 3 incoming bytes, validates against {@code supportedProtocol} and, when
     * matching, replaces itself in the inbound pipeline with the {@code next InboundHandler}.
     *
     * @param next the {@link OutboundHandler} to replace this one in the outbound pipeline
     *             upon match of protocol bytes
     */
    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public MemberProtocolEncoder(OutboundHandler[] next) {
        this.outboundHandlers = next;
    }

    @Override
    public void handlerAdded() {
        initDstBuffer(PROTOCOL_LENGTH);
    }

    @Override
    public HandlerStatus onWrite() {
        compactOrClear(dst);

        try {
            if (!clusterProtocolBuffered) {
                clusterProtocolBuffered = true;
                dst.put(stringToBytes(CLUSTER));
                // Return false because ProtocolEncoder is not ready yet; but first we need to flush protocol
                return DIRTY;
            }

            if (!isProtocolBufferDrained()) {
                // Return false because ProtocolEncoder is not ready yet; but first we need to flush protocol
                return DIRTY;
            }

            if (encoderCanReplace) {
                // replace!
                ServerConnection connection = (TcpServerConnection) channel.attributeMap().get(ServerConnection.class);
                connection.setConnectionType(ConnectionType.MEMBER);
                channel.outboundPipeline().replace(this, outboundHandlers);
            }

            return CLEAN;
        } finally {
            upcast(dst).flip();
        }
    }

    public void signalEncoderCanReplace() {
        encoderCanReplace = true;
        channel.outboundPipeline().wakeup();
    }

    /**
     * Checks if the protocol bytes have been drained.
     *
     * The protocol buffer is in write mode, so if position is 0, the protocol
     * buffer has been drained.
     *
     * @return true if the protocol buffer has been drained.
     */
    private boolean isProtocolBufferDrained() {
        return dst.position() == 0;
    }
}

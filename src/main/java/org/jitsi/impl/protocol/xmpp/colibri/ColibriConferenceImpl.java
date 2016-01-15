/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.impl.protocol.xmpp.colibri;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.jicofo.*;
import org.jitsi.protocol.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Default implementation of {@link ColibriConference} that uses Smack for
 * handling XMPP connection. Handles conference state, allocates and expires
 * channels per single conference ID. Conference ID is stored after first
 * allocate channels request.
 *
 * @author Pawel Domas
 */
public class ColibriConferenceImpl
    implements ColibriConference
{
    private final static net.java.sip.communicator.util.Logger logger
            = Logger.getLogger(ColibriConferenceImpl.class);

    /**
     * The instance of XMPP connection.
     */
    private final XmppConnection connection;
    /**
     * XMPP address of videobridge component.
     */
    private String jitsiVideobridge;

    /**
     * The {@link ColibriConferenceIQ} that stores the state of whole conference
     */
    private ColibriConferenceIQ conferenceState = new ColibriConferenceIQ();

    /**
     * Synchronization root to sync access to {@link #colibriBuilder} and
     * {@link #conferenceState}.
     */
    private final Object syncRoot = new Object();

    /**
     * Custom type of semaphore that allows only 1 thread to send initial
     * Colibri IQ that creates the conference.
     * It means that if {@link #conferenceState} has no ID then only 1 thread
     * will be allowed to send allocate request to the bridge. Other threads
     * will be suspended until we have the response. Error response to create
     * request will cause <tt>OperationFailedException</tt> on waiting threads.
     *
     * By "create request" we mean a channel allocation Colibri IQ that has no
     * conference id specified.
     */
    private final ConferenceCreationSemaphore createConfSemaphore
        = new ConferenceCreationSemaphore();

    /**
     * Utility used for building Colibri queries.
     */
    private final ColibriBuilder colibriBuilder
        = new ColibriBuilder(conferenceState);

    /**
     * Flag used to figure out if Colibri conference has been allocated during
     * last {@link #createColibriChannels(boolean, String, boolean, List)} call.
     */
    private boolean justAllocated = false;
    
    /**
     * Creates new instance of <tt>ColibriConferenceImpl</tt>.
     * @param connection XMPP connection object that wil be used by new
     *                   instance.
     */
    public ColibriConferenceImpl(XmppConnection connection)
    {
        this.connection = connection;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setJitsiVideobridge(String videobridgeJid)
    {
        if (!StringUtils.isNullOrEmpty(conferenceState.getID()))
        {
            throw new IllegalStateException(
                "Can not change the bridge on active conference");
        }
        this.jitsiVideobridge = videobridgeJid;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getJitsiVideobridge()
    {
        return this.jitsiVideobridge;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getConferenceId()
    {
        synchronized (syncRoot)
        {
            return conferenceState.getID();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setConfig(JitsiMeetConfig config)
    {
        synchronized (syncRoot)
        {
            colibriBuilder.setChannelLastN(config.getChannelLastN());
            colibriBuilder.setAdaptiveLastN(config.isAdaptiveLastNEnabled());
            colibriBuilder.setAdaptiveSimulcast(
                config.isAdaptiveSimulcastEnabled());
            colibriBuilder.setSimulcastMode(config.getSimulcastMode());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ColibriConferenceIQ createColibriChannels(
            boolean useBundle,
            String endpointName,
            boolean peerIsInitiator,
            List<ContentPacketExtension> contents)
        throws OperationFailedException
    {
        ColibriConferenceIQ allocateRequest;

        try
        {
            synchronized (syncRoot)
            {
                acquireCreateConferenceSemaphore(endpointName);

                colibriBuilder.reset();
                // Boven: test whether bundle used
                if (useBundle){
                	logger.info("Boven: Bundle used");
                }
                else{
                	logger.info("Boven: Bundle not used");
                }

                colibriBuilder.addAllocateChannelsReq(   // Boven: Here create the empty request.. creates the initial
                    useBundle, endpointName, peerIsInitiator, contents);
                

                allocateRequest = colibriBuilder.getRequest(jitsiVideobridge);
                
            }

            if (logger.isDebugEnabled())
                logger.debug(Thread.currentThread() + " sending alloc request");

            logger.info("Boven-ColibriConferenceIQ: AllocChannel Request:: " + allocateRequest.toXML());
            // FIXME retry allocation on timeout ?
            Packet response = sendAllocRequest(endpointName, allocateRequest);
            logger.info("Boven-ColibriConferenceIQ: AllocChannel Response:: " + allocateRequest.toXML());
            if (logger.isDebugEnabled())
                logger.debug(
                    Thread.currentThread() +
                        " - have alloc response? " + (response != null));

            if (response == null)
            {
                throw new OperationFailedException(
                    "Failed to allocate colibri channels: response is null."
                        + " Maybe the response timed out.",
                    OperationFailedException.NETWORK_FAILURE);
            }
            else if (response.getError() != null)
            {
                throw new OperationFailedException(
                    "Failed to allocate colibri channels: "
                        + response.getError(),
                    OperationFailedException.GENERAL_ERROR);
            }
            else if (!(response instanceof ColibriConferenceIQ))
            {
                throw new OperationFailedException(
                    "Failed to allocate colibri channels: response is not a"
                        + " colibri conference",
                    OperationFailedException.GENERAL_ERROR);
            }

            boolean conferenceExisted = getConferenceId() != null;

            /*
             * Update the complete ColibriConferenceIQ representation maintained by
             * this instance with the information given by the (current) response.
             */
            // FIXME: allocations!!! should be static method
            synchronized (syncRoot)
            {
                ColibriAnalyser analyser = new ColibriAnalyser(conferenceState);

                analyser.processChannelAllocResp((ColibriConferenceIQ) response);

                if (!conferenceExisted && getConferenceId() != null)
                {
                    justAllocated = true;
                }
            }

            /*
             * Formulate the result to be returned to the caller which is a subset
             * of the whole conference information kept by this CallJabberImpl and
             * includes the remote channels explicitly requested by the method
             * caller and their respective local channels.
             */
            return ColibriAnalyser.getResponseContents(
                        (ColibriConferenceIQ) response, contents);

        }
        finally
        {
            releaseCreateConferenceSemaphore(endpointName);
        }
    }

    /**
     * Obtains create conference semaphore. If the conference does not exist yet
     * (ID == null) then only first thread will be allowed to obtain it and all
     * other threads will have to wait for it to process response packet.
     *
     * Methods exposed for unit test purpose.
     *
     * @param endpointName the name of Colibri endpoint(conference participant)
     *
     * @return <tt>true</tt> if current thread is conference creator.
     *
     * @throws OperationFailedException if conference creator thread has failed
     *         to allocate new conference and current thread has been waiting
     *         to acquire the semaphore.
     */
    protected boolean acquireCreateConferenceSemaphore(String endpointName)
        throws OperationFailedException
    {
        return createConfSemaphore.acquire();
    }

    /**
     * Releases "create conference semaphore". Must be called to release the
     * semaphore possibly in "finally" block.
     *
     * @param endpointName the name of colibri conference endpoint(participant)
     */
    protected void releaseCreateConferenceSemaphore(String endpointName)
    {
        createConfSemaphore.release();
    }

    /**
     * Sends Colibri packet and waits for response in
     * {@link #createColibriChannels(boolean, String, boolean, List)} call.
     *
     * Exposed for unit tests purpose.
     *
     * @param endpointName Colibri endpoint name(participant)
     * @param request Colibri IQ to be send towards the bridge.
     *
     * @return <tt>Packet</tt> which is JVB response or <tt>null</tt> if
     *         the request timed out.
     */
    protected Packet sendAllocRequest(String endpointName,
                                      ColibriConferenceIQ request)
    {
        return connection.sendPacketAndGetReply(request);
    }

    /**
     * {@inheritDoc}
     */
    public boolean hasJustAllocated()
    {
        synchronized (syncRoot)
        {
            if (this.justAllocated)
            {
                this.justAllocated = false;
                return true;
            }
            return false;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expireChannels(ColibriConferenceIQ channelInfo)
    {
        ColibriConferenceIQ iq;

        synchronized (syncRoot)
        {
            colibriBuilder.reset();

            colibriBuilder.addExpireChannelsReq(channelInfo);

            iq = colibriBuilder.getRequest(jitsiVideobridge);
        }

        if (iq != null)
        {
            connection.sendPacket(iq);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateRtpDescription(
            Map<String, RtpDescriptionPacketExtension> map,
            ColibriConferenceIQ localChannelsInfo)
    {
        ColibriConferenceIQ conferenceRequest;

        synchronized (syncRoot)
        {
            colibriBuilder.reset();

            colibriBuilder.addRtpDescription(map, localChannelsInfo);

            conferenceRequest = colibriBuilder.getRequest(jitsiVideobridge);
        }

        if (conferenceRequest != null)
        {
            connection.sendPacket(conferenceRequest);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateTransportInfo(
            boolean initiator,
            Map<String, IceUdpTransportPacketExtension> map,
            ColibriConferenceIQ localChannelsInfo)
    {
        ColibriConferenceIQ iq;

        synchronized (syncRoot)
        {
            colibriBuilder.reset();

            colibriBuilder.addTransportUpdateReq(
                initiator, map, localChannelsInfo);

            iq =colibriBuilder.getRequest(jitsiVideobridge);
        }

        if (iq != null)
        {
            connection.sendPacket(iq);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateSourcesInfo(MediaSSRCMap ssrcs,
                                  MediaSSRCGroupMap ssrcGroups,
                                  ColibriConferenceIQ localChannelsInfo)
    {
        // FIXME: move to ColibriBuilder
        ColibriConferenceIQ updateIq = new ColibriConferenceIQ();

        String confId = conferenceState.getID();
        if (StringUtils.isNullOrEmpty(confId))
        {
            logger.error(
                "Have not updated SSRC info on the bridge - " +
                "no conference in progress");
            return;
        }

        updateIq.setID(confId);
        updateIq.setType(IQ.Type.SET);
        updateIq.setTo(jitsiVideobridge);

        // NOTE(gp) now that we send sources as well, I think we can scrap this
        // flag, if its initial purpose was to determine whether or not the
        // simulcast group has been added or removed.
        boolean updateNeeded = false;

        // TODO take into account the SimulcastMode and serve the correct
        // sources.
        for (ColibriConferenceIQ.Content content
            : localChannelsInfo.getContents())
        {
            ColibriConferenceIQ.Content reqContent
                = new ColibriConferenceIQ.Content(content.getName());

            boolean hasChannels = false;
            for (ColibriConferenceIQ.Channel channel : content.getChannels())
            {
                ColibriConferenceIQ.Channel reqChannel
                    = new ColibriConferenceIQ.Channel();

                reqChannel.setID(channel.getID());

                List<SourcePacketExtension> sources
                    = ssrcs.getSSRCsForMedia(content.getName());
                for (SourcePacketExtension source : sources)
                {
                    reqChannel.addSource(source.copy());
                    hasChannels = true;
                    updateNeeded = true;
                }

                if (reqChannel.getSources() == null
                    || reqChannel.getSources().isEmpty())
                {
                    // Put an empty source to remove all sources
                    SourcePacketExtension emptySource
                        = new SourcePacketExtension();
                    emptySource.setSSRC(-1l);
                    reqChannel.addSource(emptySource);

                    hasChannels = true;
                    updateNeeded = true;
                }

                List<SSRCGroup> groups
                    = ssrcGroups.getSSRCGroupsForMedia(content.getName());
                for (SSRCGroup group : groups)
                {
                    try
                    {
                        reqChannel.addSourceGroup(group.getExtensionCopy());
                        hasChannels = true;
                        updateNeeded = true;
                    }
                    catch (Exception e)
                    {
                        logger.error("Error copying extension", e);
                    }
                }
                if (groups.isEmpty())
                {
                    // Put empty source group to turn off simulcast layers
                    reqChannel.addSourceGroup(
                        SourceGroupPacketExtension.createSimulcastGroup());
                    hasChannels = true;
                    updateNeeded = true;
                }
                reqContent.addChannel(reqChannel);
            }
            if (hasChannels)
            {
                updateIq.addContent(reqContent);
            }
        }

        if (updateNeeded)
        {
            connection.sendPacketAndGetReply(updateIq);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateBundleTransportInfo(
            boolean                        initiator,
            IceUdpTransportPacketExtension transport,
            ColibriConferenceIQ            localChannelsInfo)
    {
        ColibriConferenceIQ iq;

        synchronized (syncRoot)
        {
            colibriBuilder.reset();

            colibriBuilder.addBundleTransportUpdateReq(
                initiator, transport, localChannelsInfo);

            iq = colibriBuilder.getRequest(jitsiVideobridge);
        }

        if (iq != null)
        {
            connection.sendPacket(iq);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void expireConference()
    {
        ColibriConferenceIQ iq;

        synchronized (syncRoot)
        {
            colibriBuilder.reset();

            if (StringUtils.isNullOrEmpty(conferenceState.getID()))
            {
                logger.info("Nothing to expire - no conference allocated yet");
                return;
            }

            // Expire all channels
            colibriBuilder.addExpireChannelsReq(conferenceState);

            iq = colibriBuilder.getRequest(jitsiVideobridge);

            if (iq != null)
            {
                connection.sendPacket(iq);
            }

            // Reset conference state
            conferenceState = new ColibriConferenceIQ();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean muteParticipant(ColibriConferenceIQ channelsInfo,
                                   boolean mute)
    {
        ColibriConferenceIQ request = new ColibriConferenceIQ();
        request.setID(conferenceState.getID());
        request.setName(conferenceState.getName());

        ColibriConferenceIQ.Content audioContent
            = channelsInfo.getContent("audio");

        if (audioContent == null || StringUtils.isNullOrEmpty(request.getID()))
        {
            logger.error("Failed to mute - no audio content." +
                             " Conf ID: " + request.getID());
            return false;
        }

        ColibriConferenceIQ.Content contentRequest
            = new ColibriConferenceIQ.Content(audioContent.getName());

        for (ColibriConferenceIQ.Channel channel : audioContent.getChannels())
        {
            ColibriConferenceIQ.Channel channelRequest
                = new ColibriConferenceIQ.Channel();

            channelRequest.setID(channel.getID());

            if (mute)
            {
                channelRequest.setDirection(MediaDirection.SENDONLY);
            }
            else
            {
                channelRequest.setDirection(MediaDirection.SENDRECV);
            }

            contentRequest.addChannel(channelRequest);
        }

        if (contentRequest.getChannelCount() == 0)
        {
            logger.error("Failed to mute - no channels to modify." +
                             " ConfID:" + request.getID());
            return false;
        }

        request.setType(IQ.Type.SET);
        request.setTo(jitsiVideobridge);

        request.addContent(contentRequest);

        connection.sendPacket(request);

        // FIXME wait for response and set local status

        return true;
    }

    /**
     * Sets world readable name that identifies the conference.
     * @param name the new name.
     */
    public void setName(String name)
    {
        conferenceState.setName(name);
    }

    /**
     * Gets world readable name that identifies the conference.
     * @return the name.
     */
    public String getName()
    {
        return conferenceState.getName();
    }

    /**
     * Custom type of semaphore that allows only 1 thread to send initial
     * Colibri IQ that creates the conference.
     * It means that if {@link #conferenceState} has no ID then only 1 thread
     * will be allowed to send allocate request to the bridge. Other threads
     * will be suspended until we have the response(from which we get our
     * conference ID). Error response to create request will cause
     * <tt>OperationFailedException</tt> on the threads waiting on this
     * semaphore.
     */
    class ConferenceCreationSemaphore
    {
        /**
         * Stores reference to conference creator thread instance.
         */
        private Thread creatorThread;

        /**
         * Acquires conference creation semaphore. If we don't have conference
         * ID yet then only first thread to obtain will be allowed to go through
         * and all other threads will be suspended until it finishes it's job.
         * Once we have a conference allocated all threads are allowed to go
         * through immediately.
         *
         * @return <tt>true</tt> if current thread has just become a conference
         *         creator. That is the thread that sends first channel allocate
         *         request that results in new conference created.
         *
         * @throws OperationFailedException if we are not conference creator
         *         thread and conference creator has failed to create the
         *         conference while we've been waiting on this semaphore.
         */
        public boolean acquire()
            throws OperationFailedException
        {
            synchronized (syncRoot)
            {
                String jvbInUse = jitsiVideobridge;

                if (conferenceState.getID() == null && creatorThread == null)
                {
                    creatorThread = Thread.currentThread();

                    if (logger.isDebugEnabled())
                        logger.debug("I'm the conference creator - " +
                                     Thread.currentThread().getName());

                    return true;
                }
                else
                {
                    if (logger.isDebugEnabled())
                        logger.debug(
                            "Will have to wait until the conference " +
                            "is created - " + Thread.currentThread().getName());

                    while (creatorThread != null)
                    {
                        try
                        {
                            syncRoot.wait();
                        }
                        catch (InterruptedException e)
                        {
                            throw new RuntimeException(e);
                        }
                    }

                    if (conferenceState.getID() == null)
                    {
                        throw new OperationFailedException(
                            "Creator thread has failed to " +
                                "allocate channels on: " + jvbInUse,
                            OperationFailedException.GENERAL_ERROR);
                    }

                    if (logger.isDebugEnabled())
                        logger.debug(
                            "Conference created ! Continuing with " +
                            "channel allocation -" +
                            Thread.currentThread().getName());
                }
            }
            return false;
        }

        /**
         * Releases this semaphore instance. If we're a conference creator then
         * all waiting thread will be woken up.
         */
        public void release()
        {
            synchronized (syncRoot)
            {
                if (creatorThread == Thread.currentThread())
                {
                    if (logger.isDebugEnabled())
                        logger.debug(
                            "Conference creator is releasing " +
                            "the lock - " + Thread.currentThread().getName());

                    creatorThread = null;
                    syncRoot.notifyAll();
                }
            }
        }
    }
}

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
package org.jitsi.protocol.xmpp.util;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import java.util.*;

/**
 * The map of media <tt>SourcePacketExtension</tt> encapsulates various
 * manipulation and access operations.
 *
 * @author Pawel Domas
 */
public class MediaSSRCMap
{
    /**
     * The media SSRC map storage.
     */
    private Map<String, List<SourcePacketExtension>> ssrcs
        = new HashMap<String, List<SourcePacketExtension>>();

    /**
     * Creates new empty instance of <tt>MediaSSRCMap</tt>.
     */
    public MediaSSRCMap()
    {
        ssrcs = new HashMap<String, List<SourcePacketExtension>>();
    }

    /**
     * Creates new instance of <tt>MediaSSRCMap</tt> initialized with given map
     * of media SSRCs.
     *
     * @param ssrcs initial map of media SSRCs.
     */
    private MediaSSRCMap(Map<String, List<SourcePacketExtension>> ssrcs)
    {
        this.ssrcs = ssrcs;
    }

    /**
     * Returns the list of <tt>SourcePacketExtension</tt> for given media type
     * contained in this map.
     *
     * @param media the media type for which the list of
     *              <tt>SourcePacketExtension</tt> will be returned.
     */
    public List<SourcePacketExtension> getSSRCsForMedia(String media)
    {
        List<SourcePacketExtension> ssrcList = ssrcs.get(media);
        if (ssrcList == null)
        {
            ssrcList = new ArrayList<SourcePacketExtension>();
            ssrcs.put(media, ssrcList);
        }
        return ssrcList;
    }

    /**
     * Returns all media types contained in this map.
     */
    public Set<String> getMediaTypes()
    {
        return ssrcs.keySet();
    }

    /**
     * Merges SSRCs from given map with this instance.
     *
     * @param mapToMerge the map of media SSRCs to be included in this map.
     */
    public void add(MediaSSRCMap mapToMerge)
    {
        for (String media : mapToMerge.ssrcs.keySet())
        {
            List<SourcePacketExtension> ssrcList
                = getSSRCsForMedia(media);

            // FIXME: addAll will not detect duplications
            // as .equals is not overridden
            ssrcList.addAll(mapToMerge.ssrcs.get(media));
        }
    }

    /**
     * Removes SSRCs contained in given map from this instance.
     *
     * @param mapToRemove the map that contains media SSRCs to be removed from
     *                    this instance f they are present.
     * @return the <tt>MediaSSRCMap</tt> that contains only these SSRCs that
     *         were actually removed(existed in this map).
     */
    public MediaSSRCMap remove(MediaSSRCMap mapToRemove)
    {
        MediaSSRCMap removedSSRCs = new MediaSSRCMap();
        // FIXME: fix duplication
        for (String media : mapToRemove.ssrcs.keySet())
        {
            List<SourcePacketExtension> ssrcList
                = getSSRCsForMedia(media);

            List<SourcePacketExtension> toBeRemoved
                = new ArrayList<SourcePacketExtension>();

            for (SourcePacketExtension ssrcToCheck
                    : mapToRemove.ssrcs.get(media))
            {
                for (SourcePacketExtension ssrc : ssrcList)
                {
                    if (ssrcToCheck.getSSRC() == ssrc.getSSRC())
                    {
                        toBeRemoved.add(ssrc);
                    }
                }
            }

            ssrcList.removeAll(toBeRemoved);

            removedSSRCs.getSSRCsForMedia(media).addAll(toBeRemoved);
        }
        return removedSSRCs;
    }

    /**
     * Returns shallow copy of this map. <tt>SourcePacketExtension</tt>
     * instances are not copied, but referenced from both copy and this
     * instance.
     */
    public MediaSSRCMap copyShallow()
    {
        Map<String, List<SourcePacketExtension>> mapCopy
            = new HashMap<String, List<SourcePacketExtension>>();

        for (String media : ssrcs.keySet())
        {
            List<SourcePacketExtension> listCopy
                = new ArrayList<SourcePacketExtension>(
                ssrcs.get(media));

            mapCopy.put(media, listCopy);
        }

        return new MediaSSRCMap(mapCopy);
    }

    /**
     * Returns <tt>true</tt> if this map does not contain any
     * <tt>SourcePacketExtension</tt>s or <tt>false</tt> otherwise.
     */
    public boolean isEmpty()
    {
        for (String media : ssrcs.keySet())
        {
            if (!getSSRCsForMedia(media).isEmpty())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Extracts <tt>SourcePacketExtension</tt> from given <tt>contents</tt> list
     * and creates {@link MediaSSRCMap} that reflects this contents list.
     *
     * @param contents the list of {@link ContentPacketExtension} from which
     *                 <tt>SourcePacketExtension</tt> will be extracted and put
     *                 into {@link MediaSSRCMap}.
     *
     * @return the {@link MediaSSRCMap} that describes given <tt>contents</tt>
     *         list.
     */
    public static MediaSSRCMap getSSRCsFromContent(
        List<ContentPacketExtension> contents)
    {
        Map<String, List<SourcePacketExtension>> ssrcMap
            = new HashMap<String, List<SourcePacketExtension>>();

        for (ContentPacketExtension content : contents)
        {
            RtpDescriptionPacketExtension rtpDesc
                = content.getFirstChildOfType(
                RtpDescriptionPacketExtension.class);

            List<SourcePacketExtension> ssrcPe;
            String media;

            // FIXME: different approach for SourcePacketExtension
            if (rtpDesc != null)
            {
                media = rtpDesc.getMedia();
                ssrcPe = rtpDesc.getChildExtensionsOfType(
                    SourcePacketExtension.class);
            }
            else
            {
                media = content.getName();
                ssrcPe = content.getChildExtensionsOfType(
                    SourcePacketExtension.class);
            }

            ssrcMap.put(media, ssrcPe);
        }

        return new MediaSSRCMap(ssrcMap);
    }
}

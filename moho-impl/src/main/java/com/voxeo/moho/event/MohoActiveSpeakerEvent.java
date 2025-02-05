package com.voxeo.moho.event;

import com.voxeo.moho.Mixer;
import com.voxeo.moho.Participant;

public class MohoActiveSpeakerEvent extends MohoMediaEvent<Mixer> implements ActiveSpeakerEvent {

    private Participant[] activeSpeakers;

    public MohoActiveSpeakerEvent(Mixer source, Participant[] activeSpeakers) {
        super(source);
        this.activeSpeakers = activeSpeakers;
    }

    public void setActiveSpeakers(Participant[] activeSpeakers) {
        this.activeSpeakers = activeSpeakers;
    }

    public Participant[] getActiveSpeakers() {
        return activeSpeakers;
    }

}

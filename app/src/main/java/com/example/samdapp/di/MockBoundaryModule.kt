package com.example.samdapp.di

import com.example.samdapp.data.config.AndroidSyncWindowProvider
import com.example.samdapp.data.connectivity.AndroidNetworkMonitor
import com.example.samdapp.data.doctor.MockDoctorPrescriptionInbox
import com.example.samdapp.data.media.AndroidAilmentAudioRecorder
import com.example.samdapp.data.mock.MockVitalsSource
import com.example.samdapp.data.transcription.AndroidSpeechRecognizerService
import com.example.samdapp.domain.config.SyncWindowProvider
import com.example.samdapp.domain.connectivity.NetworkMonitor
import com.example.samdapp.domain.doctor.DoctorPrescriptionInbox
import com.example.samdapp.domain.media.AilmentAudioRecorder
import com.example.samdapp.domain.transcription.TranscriptionService
import com.example.samdapp.domain.vitalssource.VitalsSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The only place outside [MockVitalsSource]/[AndroidSpeechRecognizerService]/[AndroidNetworkMonitor]
 * themselves that references those concrete types — everywhere else touches only the domain interfaces. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MockBoundaryModule {

    @Binds @Singleton
    abstract fun bindVitalsSource(impl: MockVitalsSource): VitalsSource

    @Binds @Singleton
    abstract fun bindTranscriptionService(impl: AndroidSpeechRecognizerService): TranscriptionService

    @Binds @Singleton
    abstract fun bindNetworkMonitor(impl: AndroidNetworkMonitor): NetworkMonitor

    @Binds @Singleton
    abstract fun bindAilmentAudioRecorder(impl: AndroidAilmentAudioRecorder): AilmentAudioRecorder

    @Binds @Singleton
    abstract fun bindSyncWindowProvider(impl: AndroidSyncWindowProvider): SyncWindowProvider

    @Binds @Singleton
    abstract fun bindDoctorPrescriptionInbox(impl: MockDoctorPrescriptionInbox): DoctorPrescriptionInbox
}

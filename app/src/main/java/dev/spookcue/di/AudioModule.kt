package dev.spookcue.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.spookcue.audio.SttEngine
import dev.spookcue.audio.SttEngineImpl
import dev.spookcue.audio.VadEngine
import dev.spookcue.audio.VadEngineImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    @Singleton
    abstract fun bindVadEngine(impl: VadEngineImpl): VadEngine

    @Binds
    @Singleton
    abstract fun bindSttEngine(impl: SttEngineImpl): SttEngine
}

package com.clipride.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.hammerhead.karooext.KarooSystemService
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideKarooSystemService(@ApplicationContext context: Context): KarooSystemService {
        return KarooSystemService(context)
    }
}

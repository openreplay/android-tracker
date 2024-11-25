package com.openreplay.sampleapp.ui.home

import android.annotation.SuppressLint
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

@SuppressLint("SimpleDateFormat")
class HomeViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "This is home Fragment"
    }
    val text: LiveData<String> = _text


    val readableTimeFlow = flow<String> {
        while (true) {
            delay(1000)
            val yourmilliseconds = System.currentTimeMillis()
            val sdf = SimpleDateFormat("MMM dd,yyyy HH:mm:ss")
            val resultdate = Date(yourmilliseconds)
            emit(sdf.format(resultdate))
        }
    }

    init {
        viewModelScope.launch {
            readableTimeFlow.collect { readableTime ->
                _text.postValue(readableTime)
            }
        }
    }
}
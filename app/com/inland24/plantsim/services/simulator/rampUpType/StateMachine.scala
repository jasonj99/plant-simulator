/*
 * Copyright (c) 2017 joesan @ http://github.com/joesan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.inland24.plantsim.services.simulator.rampUpType

import com.inland24.plantsim.models.PowerPlantConfig.RampUpTypeConfig
import com.inland24.plantsim.models.PowerPlantSignal
import com.inland24.plantsim.models.PowerPlantSignal.{DispatchAlert, Genesis, Transition}
import com.inland24.plantsim.models.PowerPlantState._
import org.joda.time.{DateTime, DateTimeZone, Seconds}

import scala.concurrent.duration._


case class StateMachine(
  cfg: RampUpTypeConfig,
  setPoint: Double,
  lastSetPointReceivedAt: DateTime,
  lastRampTime: DateTime,
  oldState: com.inland24.plantsim.models.PowerPlantState,
  newState: com.inland24.plantsim.models.PowerPlantState,
  events: Vector[PowerPlantSignal],
  signals: Map[String, String]
) {
  override def toString: String = {
    s"""
       |id = ${cfg.id}, setPoint = $setPoint, lastSetPointReceivedAt = $lastSetPointReceivedAt, lastRampTime = $lastRampTime
       |oldState = $oldState, newState = $newState
       |events = $events.mkString(",")
       |signals = $signals.mkString(",")
    """.stripMargin
  }
}
object StateMachine {

  val isAvailableSignalKey  = "isAvailable"
  val isDispatchedSignalKey = "isDispatched"
  val activePowerSignalKey  = "activePower"

  val unAvailableSignals = Map(
    activePowerSignalKey  -> 0.1.toString, // the power does not matter when the plant is unavailable for steering
    isDispatchedSignalKey -> false.toString,
    isAvailableSignalKey  -> false.toString // indicates if the power plant is not available for steering
  )

  // Utility method that will clear and emit the events to the outside world
  def popEvents(state: StateMachine): (Seq[PowerPlantSignal], StateMachine) = {
    (state.events, state.copy(events = Vector.empty))
  }

  // The starting state of our StateMachine
  def init(cfg: RampUpTypeConfig) = {
    StateMachine(
      cfg = cfg,
      oldState = Init,
      newState = Init,
      setPoint = cfg.minPower, // By default, we start with the minimum power as our setPoint
      lastSetPointReceivedAt = DateTime.now(DateTimeZone.UTC),
      lastRampTime = DateTime.now(DateTimeZone.UTC),
      events = Vector(
        Genesis(
          timeStamp = DateTime.now(DateTimeZone.UTC),
          newState = Init,
          powerPlantConfig = cfg
        )
      ),
      signals = Map(
        activePowerSignalKey  -> cfg.minPower.toString, // be default this plant operates at min power
        isDispatchedSignalKey -> false.toString,
        isAvailableSignalKey  -> true.toString // indicates if the power plant is available for steering
      )
    )
  }

  // Utility methods to check state transition timeouts
  def isDispatched(state: StateMachine): Boolean = {
    val collectedSignal = state.signals.collect { // to dispatch, you got to be available
      case (key, value) if key == activePowerSignalKey => key -> value
    }

    collectedSignal.nonEmpty && (collectedSignal(activePowerSignalKey).toDouble >= state.setPoint)
  }

  def isReturnedToNormal(state: StateMachine): Boolean = {
    val collectedSignal = state.signals.collect { // to ReturnToNormal, you got to be available
      case (key, value) if key == activePowerSignalKey => key -> value
    }

    collectedSignal.nonEmpty && (collectedSignal(activePowerSignalKey).toDouble <= state.cfg.minPower)
  }

  def isTimeForRamp(timeSinceLastRamp: DateTime, rampRateInSeconds: FiniteDuration): Boolean = {
    val elapsed = Seconds.secondsBetween(DateTime.now(DateTimeZone.UTC), timeSinceLastRamp).multipliedBy(-1)
    elapsed.getSeconds.seconds >= rampRateInSeconds
  }

  // State transition methods
  def active(stm: StateMachine): StateMachine = {
    stm.copy(
      newState = com.inland24.plantsim.models.PowerPlantState.Active,
      oldState = stm.newState,
      signals = Map(
        activePowerSignalKey  -> stm.cfg.minPower.toString, // be default this plant operates at min power
        isDispatchedSignalKey -> false.toString,
        isAvailableSignalKey  -> true.toString // indicates if the power plant is available for steering
      ),
      events = Vector(
        Transition(
          newState = com.inland24.plantsim.models.PowerPlantState.Active,
          oldState = stm.newState,
          powerPlantConfig = stm.cfg
        )
      ) ++ stm.events
    )
  }

  def outOfService(stm: StateMachine): StateMachine = {
    stm.copy(
      newState = OutOfService,
      oldState = stm.newState,
      signals = unAvailableSignals,
      events = Vector(
        Transition(
          newState = OutOfService,
          oldState = stm.newState,
          powerPlantConfig = stm.cfg,
          timeStamp = DateTime.now(DateTimeZone.UTC)
        )
      ) ++ stm.events
    )
  }

  def returnToService(stm: StateMachine): StateMachine = {
    stm.copy(
      setPoint = stm.cfg.minPower,
      newState = ReturnToService,
      oldState = stm.newState,
      signals = unAvailableSignals,
      events = Vector(
        Transition(
          newState = ReturnToService,
          oldState = stm.newState,
          powerPlantConfig = stm.cfg
        )
      ) ++ stm.events
    )
  }

  def dispatch(stm: StateMachine, setPoint: Double): StateMachine = {
    // 1. Check if the setPoint or the power to be dispatched is greater than the minPower
    if (setPoint <= stm.cfg.minPower) {
      stm.copy(
        events = Vector(
          DispatchAlert(
            msg = "TODO ADD a meaningful message",
            powerPlantConfig = stm.cfg,
            timeStamp = DateTime.now(DateTimeZone.UTC)
          )
        ) ++ stm.events
      )
    } else if (setPoint > stm.cfg.maxPower) { // If SetPoint greater than maxPower, curtail it
      stm.copy(
        setPoint = stm.cfg.maxPower, // curtailing the SetPoint to maxPower
        lastSetPointReceivedAt = DateTime.now(DateTimeZone.UTC),
        oldState = stm.newState,
        newState = RampUp,
        events = Vector(
          Transition(
            oldState = stm.newState,
            newState = RampUp,
            powerPlantConfig = stm.cfg
          ),
          DispatchAlert(
            s"requested dispatchPower = $setPoint is greater than " +
              s"maxPower = ${stm.cfg.maxPower} capacity of the PowerPlant, " +
              s"so curtailing at maxPower for PowerPlant ${stm.cfg.id}",
            stm.cfg
          )
        ) ++ stm.events
      )
    } else {
      stm.copy(
        setPoint = setPoint,
        lastSetPointReceivedAt = DateTime.now(DateTimeZone.UTC),
        oldState = stm.newState,
        newState = RampUp,
        events = Vector(
          Transition(
            oldState = stm.newState,
            newState = RampUp,
            powerPlantConfig = stm.cfg
          )
        ) ++ stm.events
      )
    }
  }

  def rampDownCheck(state: StateMachine): StateMachine = { // ReturnToNormal means RampDown
    if (isTimeForRamp(state.lastRampTime, state.cfg.rampRateInSeconds)) {
      val collectedSignal = state.signals.collect { // to rampDown, you got to be in dispatched state
        case (key, value) if key == isDispatchedSignalKey && value.toBoolean => key -> value
      }

      if (collectedSignal.nonEmpty && state.signals.get(activePowerSignalKey).isDefined) {
        val currentActivePower = state.signals(activePowerSignalKey).toDouble
        // check if the newActivePower is lesser than the minPower
        if (currentActivePower - state.cfg.rampPowerRate <= state.cfg.minPower) { // if true, this means we have ramped down to the required minPower!
          state.copy(
            oldState = state.newState,
            newState = Active,
            events = Vector(
              Transition(
                newState = Active,
                oldState = state.newState,
                powerPlantConfig = state.cfg
              )
            ) ++ state.events,
            signals = Map(
              isDispatchedSignalKey -> false.toString,
              activePowerSignalKey  -> state.cfg.minPower.toString,
              isAvailableSignalKey  -> true.toString // the plant is available and not faulty!
            )
          )
        } else { // else, we do one RampDown attempt
          state.copy(
            lastRampTime = DateTime.now(DateTimeZone.UTC),
            newState = RampDown,
            oldState = state.newState,
            signals = Map(
              isDispatchedSignalKey -> true.toString,
              activePowerSignalKey  -> (currentActivePower - state.cfg.rampPowerRate).toString,
              isAvailableSignalKey  -> true.toString // the plant is still available and not faulty!
            )
          )
        }
      } else state
    } else state
  }

  def rampUpCheck(stm: StateMachine): StateMachine = {
    if (isTimeForRamp(stm.lastRampTime, stm.cfg.rampRateInSeconds)) {
      val collectedSignal = stm.signals.collect { // to dispatch, you got to be available
        case (key, value) if key == isAvailableSignalKey && value.toBoolean => key -> value
      }

      val newState = if (collectedSignal.nonEmpty && stm.signals.get(activePowerSignalKey).isDefined) {
        val currentActivePower = stm.signals(activePowerSignalKey).toDouble
        // check if the newActivePower is greater than setPoint
        if (currentActivePower + stm.cfg.rampPowerRate >= stm.setPoint) { // This means we have fully ramped up to the setPoint
          stm.copy(
            newState = Dispatched,
            oldState = stm.newState,
            signals = Map(
              isDispatchedSignalKey -> true.toString,
              activePowerSignalKey  -> stm.setPoint.toString,
              isAvailableSignalKey  -> true.toString // the plant is still available and not faulty!
            ),
            events = Vector(
              Transition(
                oldState = stm.newState,
                newState = Dispatched,
                powerPlantConfig = stm.cfg
              )
            ) ++ stm.events
          )
        }
        else { // We still have to RampUp
          stm.copy(
            lastRampTime = DateTime.now(DateTimeZone.UTC),
            newState = RampUp,
            oldState = stm.newState,
            signals = Map(
              isDispatchedSignalKey -> false.toString,
              activePowerSignalKey  -> (currentActivePower + stm.cfg.rampPowerRate).toString,
              isAvailableSignalKey  -> true.toString // the plant is still available and not faulty!
            )
          )
        }
      } else stm
      newState
    } else stm
  }
}
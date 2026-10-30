//! Speed: how far a move may go sideways after the one before it. Vanilla's `LivingEntity.travelInAir`
//! carries a tick's velocity into the next times a friction (the block's, times 0.91, on the ground;
//! 0.91 in the air), then adds the input's acceleration: the movement speed times 0.216 / friction³ on
//! the ground, the flying speed in the air (0.026 sprinting); a sprint jump adds 0.2 more. So a move is at
//! most the previous one times that friction plus the acceleration, plus any push the server gave the
//! player (knockback, explosions). Everything is taken at its largest: sprinting, the slipperiest block.
use std::cell::Cell;

/// `Player.getFlyingSpeed` while sprinting.
const AIR_ACCELERATION: f64 = 0.026;
/// `LivingEntity.jumpFromGround`'s sprint boost.
const SPRINT_JUMP: f64 = 0.2;
/// `ServerPlayer`'s sprint modifier on the movement speed (+30 %), which the server's guests don't
/// hold in their attributes.
const SPRINT: f64 = 1.3;

/// What the server knows of one move, sideways.
#[derive(Clone, Copy, Debug)]
pub struct Move {
    /// Horizontal distance from where the server had the player.
    pub distance: f64,
    /// Client ticks it covers (ServerboundClientTickEndPacket since the last move; a still client
    /// skips moves).
    pub ticks: u32,
    /// The client said it was on the ground after the move before (this tick starts on it).
    pub on_ground: bool,
    /// The friction of the block under where this move starts (`getBlockPosBelowThatAffectsMyMovement`).
    pub friction: f64,
    /// The movement speed attribute, effects included.
    pub speed: f64,
    /// The move goes up (a jump can start it).
    pub rising: bool,
}

/// A player's last move, for the next one.
#[derive(Clone, Debug, Default)]
pub struct Tracker {
    /// The previous move's distance a tick (at most what physics allowed), and the friction carried into
    /// the next tick. None: unknown (joined, teleported, out of a vehicle, gliding...).
    last: Option<(f64, f64)>,
    /// The largest push the server sent the player lately, horizontal blocks a tick. A `Cell`: it's set
    /// where the packet is sent.
    push: Cell<f64>,
}

impl Tracker {
    /// Forget the last move: the next one isn't judged.
    pub fn reset(&mut self) {
        self.last = None;
    }

    /// The server sent the player this velocity (ClientboundSetEntityMotionPacket, an explosion's push).
    pub fn pushed(&self, velocity: [f64; 3]) {
        let h = velocity[0].hypot(velocity[2]);
        self.push.set(self.push.get().max(h));
    }

    /// Judge `m`: how much farther than vanilla allows it went, if it did.
    pub fn check(&mut self, m: Move, tolerance: f64) -> Option<f64> {
        let ticks = m.ticks.max(1);
        let ground = m.on_ground.then_some(m.friction);
        let acceleration = ground.map_or(AIR_ACCELERATION, |f| {
            m.speed * SPRINT * (0.216_000_02 / (f * f * f))
        });
        let carried_next = ground.unwrap_or(1.0) * 0.91;
        let push = self.push.get();
        // The push reaches the client a round trip later: it fades over about a second.
        self.push.set(if push < 1.0E-3 { 0.0 } else { push * 0.95 });
        let Some((per_tick, carried)) = self.last else {
            self.last = Some(((m.distance / f64::from(ticks)).min(1.0), carried_next));
            return None;
        };
        let jump = if m.on_ground && m.rising {
            SPRINT_JUMP
        } else {
            0.0
        };
        let mut step = (per_tick * carried).max(push) + acceleration + jump;
        let mut limit = step;
        for _ in 1..ticks {
            step = step * carried_next + acceleration;
            limit += step;
        }
        self.last = Some(((m.distance.min(limit) / f64::from(ticks)), carried_next));
        let excess = m.distance - limit - tolerance;
        (excess > 0.0).then_some(excess)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const WALK: f64 = 0.1;

    fn ground(distance: f64) -> Move {
        Move {
            distance,
            ticks: 1,
            on_ground: true,
            friction: 0.6,
            speed: WALK,
            rising: false,
        }
    }

    /// A vanilla player sprinting from a stand on stone: `travelInAir` tick by tick.
    fn sprint(ticks: usize) -> Vec<f64> {
        let mut v = 0.0;
        (0..ticks)
            .map(|_| {
                let d = v + WALK * SPRINT;
                v = d * 0.6 * 0.91;
                d
            })
            .collect()
    }

    #[test]
    fn vanilla_sprinting_and_jumping_pass() {
        let mut t = Tracker::default();
        assert_eq!(t.check(ground(0.0), 0.05), None);
        for d in sprint(40) {
            assert_eq!(t.check(ground(d), 1e-9), None, "{d}");
        }
        // A sprint jump: the boost, then the air.
        let top = sprint(40)[39];
        let jump = top * 0.546 + 0.13 + 0.2;
        assert_eq!(
            t.check(
                Move {
                    rising: true,
                    ..ground(jump)
                },
                1e-9
            ),
            None
        );
        let air = jump * 0.546 + AIR_ACCELERATION;
        assert_eq!(
            t.check(
                Move {
                    on_ground: false,
                    ..ground(air)
                },
                1e-9
            ),
            None
        );
    }

    #[test]
    fn faster_than_vanilla_is_caught() {
        let mut t = Tracker::default();
        t.check(ground(0.0), 0.05);
        let flags = (0..40)
            .filter(|_| t.check(ground(0.43), 0.05).is_some())
            .count();
        assert!(flags >= 39, "{flags}");
        // Stepping up to it a tick at a time doesn't help.
        let mut t = Tracker::default();
        t.check(ground(0.0), 0.05);
        let flags = sprint(40)
            .iter()
            .filter(|d| t.check(ground(*d * 1.5), 0.05).is_some())
            .count();
        assert!(flags >= 30, "{flags}");
    }

    #[test]
    fn ice_skipped_moves_and_pushes_are_allowed() {
        // Blue ice: a slower start, a much higher top speed.
        let mut t = Tracker::default();
        t.check(
            Move {
                friction: 0.989,
                ..ground(0.0)
            },
            0.05,
        );
        let mut v = 0.0;
        for _ in 0..200 {
            let d = v + WALK * SPRINT * 0.216 / (0.989f64 * 0.989 * 0.989);
            v = d * 0.989 * 0.91;
            assert_eq!(
                t.check(
                    Move {
                        friction: 0.989,
                        ..ground(d)
                    },
                    1e-9
                ),
                None
            );
        }
        // Two ticks in one move.
        let mut t = Tracker::default();
        t.check(ground(0.0), 0.05);
        let s = sprint(2);
        assert_eq!(
            t.check(
                Move {
                    ticks: 2,
                    ..ground(s[0] + s[1])
                },
                1e-9
            ),
            None
        );
        // Knockback.
        let mut t = Tracker::default();
        t.check(ground(0.0), 0.05);
        t.pushed([0.6, 0.4, 0.0]);
        assert_eq!(t.check(ground(0.6), 0.05), None);
        assert!(t.check(ground(2.0), 0.05).is_some());
    }
}

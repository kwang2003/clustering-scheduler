package tw.com.shihyu.clustering.scheduler.quorum;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A contender in leader election
 * 
 * @author Matt S.Y. Ho
 *
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class Contender {

  private String id;
  private boolean leader;

}

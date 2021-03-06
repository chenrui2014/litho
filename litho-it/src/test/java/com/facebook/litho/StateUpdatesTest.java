/**
 * Copyright (c) 2017-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.litho;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import android.os.Looper;

import com.facebook.litho.ComponentLifecycle.StateContainer;
import com.facebook.litho.testing.ComponentTestHelper;
import com.facebook.litho.testing.testrunner.ComponentsTestRunner;
import com.facebook.litho.testing.util.InlineLayoutSpec;
import com.facebook.yoga.YogaAlign;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.reflect.Whitebox;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowLooper;

import static com.facebook.litho.ComponentLifecycle.StateUpdate;
import static com.facebook.litho.SizeSpec.EXACTLY;
import static com.facebook.litho.SizeSpec.makeSizeSpec;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

@RunWith(ComponentsTestRunner.class)
public class StateUpdatesTest {
  private static final int LIFECYCLE_TEST_ID = 1;
  private static final int INITIAL_COUNT_STATE_VALUE = 4;

  private int mWidthSpec;
  private int mHeightSpec;

  private final ComponentLifecycle mLifecycle = new ComponentLifecycle() {
    @Override
    int getId() {
      return LIFECYCLE_TEST_ID;
    }

    @Override
    protected boolean hasState() {
      return true;
    }

    @Override
    protected void createInitialState(ComponentContext c, Component component) {
      TestComponent testComponent = (TestComponent) component;
      testComponent.mStateContainer.mCount = INITIAL_COUNT_STATE_VALUE;
    }

    @Override
    protected void transferState(
        ComponentContext c,
        StateContainer stateContainer,
        Component component) {
      TestStateContainer stateContainerImpl = (TestStateContainer) stateContainer;
      TestComponent newTestComponent = (TestComponent) component;
      newTestComponent.mStateContainer.mCount = stateContainerImpl.mCount;
    }
  };

  private static class TestStateUpdate implements StateUpdate {

    @Override
    public void updateState(StateContainer stateContainer, Component component) {
      TestStateContainer stateContainerImpl = (TestStateContainer) stateContainer;
      TestComponent componentImpl = (TestComponent) component;
      System.out.println("1 " + componentImpl.mStateContainer);
      System.out.println("2 " + stateContainerImpl);
      componentImpl.mStateContainer.mCount = stateContainerImpl.mCount + 1;
    }
  }

  static class TestComponent<L extends ComponentLifecycle>
      extends Component<L> implements Cloneable {

    private TestStateContainer mStateContainer;
    private TestComponent shallowCopy;
    private int mId;
    private static final AtomicInteger sIdGenerator = new AtomicInteger(0);

    public TestComponent(L component) {
      super(component);
      mStateContainer = new TestStateContainer();
      mId = sIdGenerator.getAndIncrement();
    }

    @Override
    public String getSimpleName() {
      return "TestComponent";
    }

    int getCount() {
      return mStateContainer.mCount;
    }

    @Override
    public Component makeShallowCopy() {
      return this;
    }

    @Override
    Component makeShallowCopyWithNewId() {
      shallowCopy = (TestComponent) super.makeShallowCopy();
      shallowCopy.mId = sIdGenerator.getAndIncrement();
      return shallowCopy;
    }

    TestComponent getComponentForStateUpdate() {
      if (shallowCopy == null) {
        return this;
      }
      return shallowCopy.getComponentForStateUpdate();
    }

    @Override
    protected int getId() {
      return mId;
    }

    @Override
    protected StateContainer getStateContainer() {
      return mStateContainer;
    }
  }

  static class TestStateContainer implements StateContainer {
    protected int mCount;
  }

  private ShadowLooper mLayoutThreadShadowLooper;
  private ComponentContext mContext;
  private TestComponent mTestComponent;
  private ComponentTree mComponentTree;

  @Before
  public void setup() throws Exception {
    mContext = new ComponentContext(RuntimeEnvironment.application);
    mWidthSpec = makeSizeSpec(39, EXACTLY);
    mHeightSpec = makeSizeSpec(41, EXACTLY);

    mLayoutThreadShadowLooper = Shadows.shadowOf(
        (Looper) Whitebox.invokeMethod(
            ComponentTree.class,
            "getDefaultLayoutThreadLooper"));
    mTestComponent = new TestComponent(mLifecycle);

    mComponentTree = ComponentTree.create(mContext, mTestComponent)
        .incrementalMount(false)
        .layoutDiffing(false)
        .build();
    final LithoView lithoView = new LithoView(mContext);
    lithoView.setComponentTree(mComponentTree);
    lithoView.onAttachedToWindow();
    ComponentTestHelper.measureAndLayout(lithoView);
  }

  @Test(expected = RuntimeException.class)
  public void testCrashOnSameComponentKey() {
    final Component child1 = new TestComponent(mLifecycle);
    final Component child2 = new TestComponent(mLifecycle);
    final Component component = new InlineLayoutSpec() {
      @Override
      protected ComponentLayout onCreateLayout(ComponentContext c) {
        return Column.create(c)
            .child(child1)
            .child(child2)
            .build();
      }
    };
    final ComponentTree componentTree = ComponentTree.create(mContext, component)
        .incrementalMount(false)
        .layoutDiffing(false)
        .build();
    final LithoView lithoView = new LithoView(mContext);
    lithoView.setComponentTree(componentTree);
    lithoView.onAttachedToWindow();
    ComponentTestHelper.measureAndLayout(lithoView);
  }

  @Test(expected = RuntimeException.class)
  public void testCrashOnSameComponentKeyNestedContainers() {
    final Component child1 = new TestComponent(mLifecycle);
    final Component component = new InlineLayoutSpec() {
      @Override
      protected ComponentLayout onCreateLayout(ComponentContext c) {
        return Column.create(c)
            .child(
                Column.create(c)
                    .child(child1))
            .child(
                Column.create(c)
                    .child(child1))
            .build();
      }
    };
    final ComponentTree componentTree = ComponentTree.create(mContext, component)
        .incrementalMount(false)
        .layoutDiffing(false)
        .build();
    final LithoView lithoView = new LithoView(mContext);
    lithoView.setComponentTree(componentTree);
    lithoView.onAttachedToWindow();
    ComponentTestHelper.measureAndLayout(lithoView);
  }

  @Test
  public void testKeepInitialStateValues() {
    TestStateContainer previousStateContainer =
        (TestStateContainer) getStateContainersMap().get(mTestComponent.getGlobalKey());
    assertNotNull(previousStateContainer);
    assertEquals(INITIAL_COUNT_STATE_VALUE, previousStateContainer.mCount);
  }

  @Test
  public void testKeepUpdatedStateValue() {
    mComponentTree.updateStateAsync(mTestComponent.getGlobalKey(), new TestStateUpdate());
    mLayoutThreadShadowLooper.runOneTask();
    TestStateContainer previousStateContainer =
        (TestStateContainer) getStateContainersMap().get(mTestComponent.getGlobalKey());
    assertNotNull(previousStateContainer);
    assertEquals(INITIAL_COUNT_STATE_VALUE + 1, previousStateContainer.mCount);
  }

  @Test
  public void testClearAppliedStateUpdates() {
    mComponentTree.updateStateAsync(mTestComponent.getGlobalKey(), new TestStateUpdate());
    assertEquals(1, getPendingStateUpdatesForComponent(mTestComponent).size());
    mLayoutThreadShadowLooper.runOneTask();
    assertNull(getPendingStateUpdatesForComponent(mTestComponent.getComponentForStateUpdate()));
  }

  @Test
  public void testEnqueueStateUpdate() {
    mComponentTree.updateStateAsync(mTestComponent.getGlobalKey(), new TestStateUpdate());
    assertEquals(1, getPendingStateUpdatesForComponent(mTestComponent).size());
    mLayoutThreadShadowLooper.runOneTask();
    mComponentTree.updateStateAsync(mTestComponent.getGlobalKey(), new TestStateUpdate());
    assertEquals(
        INITIAL_COUNT_STATE_VALUE + 1,
        ((TestStateContainer) getStateContainersMap().get(mTestComponent.getGlobalKey())).mCount);
    assertEquals(
        1,
        getPendingStateUpdatesForComponent(mTestComponent.getComponentForStateUpdate()).size());
  }

  @Test
  public void testSetInitialStateValue() {
    assertEquals(INITIAL_COUNT_STATE_VALUE, mTestComponent.getCount());
  }

  @Test
  public void testUpdateState() {
    mComponentTree.updateStateAsync(mTestComponent.getGlobalKey(), new TestStateUpdate());
    mLayoutThreadShadowLooper.runOneTask();
    assertEquals(
        INITIAL_COUNT_STATE_VALUE + 1,
        mTestComponent.getComponentForStateUpdate().getCount());
  }

  @Test
  public void testTransferState() {
    mComponentTree.updateStateAsync(mTestComponent.getGlobalKey(), new TestStateUpdate());
    mLayoutThreadShadowLooper.runOneTask();
    mComponentTree.setSizeSpec(mWidthSpec, mHeightSpec);
    assertEquals(
        INITIAL_COUNT_STATE_VALUE + 1,
        mTestComponent.getComponentForStateUpdate().getCount());
  }

  @Test
  public void testTransferAndUpdateState() {
    mComponentTree.updateStateAsync(mTestComponent.getGlobalKey(), new TestStateUpdate());
    mLayoutThreadShadowLooper.runOneTask();

    mComponentTree.updateStateAsync(mTestComponent.getGlobalKey(), new TestStateUpdate());
    mLayoutThreadShadowLooper.runOneTask();
    assertEquals(
        INITIAL_COUNT_STATE_VALUE + 2,
        mTestComponent.getComponentForStateUpdate().getCount());
  }

  private StateHandler getStateHandler() {
    return Whitebox.getInternalState(mComponentTree, "mStateHandler");
  }

  private Map<String, StateContainer> getStateContainersMap() {
    return getStateHandler().getStateContainers();
  }

  private Map<String, List<StateUpdate>> getPendingStateUpdates() {
    return getStateHandler().getPendingStateUpdates();
  }

  private List<StateUpdate> getPendingStateUpdatesForComponent(Component component) {
    return getPendingStateUpdates().get(component.getGlobalKey());
  }
}
